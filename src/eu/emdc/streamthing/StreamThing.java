package eu.emdc.streamthing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import eu.emdc.streamthing.message.*;
import eu.emdc.streamthing.stats.MessageStatistics;
import eu.emdc.streamthing.transport.TransportControl;

import peersim.cdsim.CDProtocol;
import peersim.config.Configuration;
import peersim.config.FastConfig;
import peersim.core.CommonState;
import peersim.core.Network;
import peersim.core.Node;
import peersim.edsim.EDProtocol;
import peersim.transport.Transport;

public class StreamThing implements Cloneable, CDProtocol, EDProtocol {

	private static final String PAR_CAPACITY = "capacityfile";
	private static final String NODE_LATENCY = "latencyfile";
	
	/* implementation */
	protected String prefix;
	protected StreamManager m_streamManager;
	protected TurbulenceManager m_turbulenceManager;
	protected TransportControl m_transportControl;
	public boolean hasJoined = false;
	public int m_myStreamNodeId;
	protected Map<Integer, Integer> m_streamsISubscribeTo;
	protected List<Integer> m_streamsIPublish;
	protected int m_uploadBandwidthOfStreamsIPublish = 0;
	protected Map<Integer, List<Integer> > m_latestPing; 
	
	static public int m_turbulenceCount = 0;
	
	static public Map<Integer, Long> m_streamIdToNodeId = new HashMap<Integer, Long>();

	static public Map<Integer, Integer> m_videoStreamToStreamNodeId = new HashMap<Integer, Integer>();
	
	static public Map<Integer, NodeWorld> m_videoStreamIdToMulticastTreeMap = new HashMap<Integer, NodeWorld>();
	
	static public int GetStreamIdFromNodeId(long nodeId) {
		Iterator<Entry<Integer, Long>> iter = m_streamIdToNodeId.entrySet().iterator();
		while (iter.hasNext()) {
			Entry<Integer, Long> entry = iter.next();
			if (entry.getValue() == nodeId)
				return entry.getKey();
		}
			
		return -1;
	}
	
	static public Node GetNodeFromNodeId(long nodeId) {
		for (int i = 0; i < Network.size(); i++) {
			if (Network.get(i).getID() == nodeId)
				return Network.get(i);
		}
		return null;
	}
	
	public StreamThing(String prefix) {
		this.prefix = prefix;
		NodeConfig.InitialiseUploadCapacity(Configuration.getString(prefix + "." + PAR_CAPACITY));
		NodeConfig.InitialiseLatencyMap(Configuration.getString(prefix + "." + NODE_LATENCY));
		m_streamsISubscribeTo = new HashMap<Integer, Integer>();
		m_latestPing = new HashMap<Integer, List<Integer> >();
	}
	
	public long latency(Node src, Node dest) {
		DelayTuple dt = null;
		
		StreamThing s_src = (StreamThing)src.getProtocol(Configuration.lookupPid("streamthing")); 
		StreamThing s_dest = (StreamThing)dest.getProtocol(Configuration.lookupPid("streamthing")); 
		if (s_src.m_myStreamNodeId == -1 || s_dest.m_myStreamNodeId == -1)
			return 5; 
		
		if ((dt = NodeConfig.GetDelayTupleForNodePair(s_src.m_myStreamNodeId, s_dest.m_myStreamNodeId)) != null) {
			
			long x = (long)dt.GetMinDelay() + CommonState.r.nextLong((long)(dt.GetMaxDelay() - dt.GetMinDelay()));
			return x;
		}
		
		return 5; // assumed standard latency
	}
	
	public int TotalAmountOfUpload (){
		Iterator<Entry<Integer, Integer>> iter = m_streamsISubscribeTo.entrySet().iterator();
		int sum = 0;
		while (iter.hasNext()) {
			Entry<Integer, Integer> entry = iter.next();
			NodeWorld nw = m_videoStreamIdToMulticastTreeMap.get (entry.getKey());
			
			if(nw != null)
			{
				int num;
				if (nw.GetChildren(m_myStreamNodeId) == null)
					num = 0;
				else
					num = nw.GetChildren(m_myStreamNodeId).size ();
				sum += entry.getValue() * num;
			}
		}
		
		sum += m_uploadBandwidthOfStreamsIPublish;
		
		if (m_turbulenceManager != null) {
			sum += m_turbulenceManager.getTurbulenceBandwidth();
		}
		//System.out.println(m_myStreamNodeId + " sum:  "  + sum + " queue: " + m_transportControl.getQueueSize());
		
		return sum;
	}
	
	public void cleanup (){
		m_streamsISubscribeTo.clear ();
		m_latestPing.clear ();
		m_streamsIPublish.clear ();
	}

	@Override
	public void nextCycle(Node node, int protocolID) {
		// where running event driven
	}

	@Override
	public void processEvent(Node node, int pid, Object event) {
		if (event instanceof TransportWithDelayEvent) 
		{
			Transport transport = (Transport) node.getProtocol(FastConfig
					.getTransport(pid));
			TransportWithDelayEvent e = (TransportWithDelayEvent) event;
			if (m_myStreamNodeId >= 0)
				transport.send(e.src, e.dest, e.msg, e.pid);
			
		} 
		else if (event instanceof StreamEvent) 
		{
			handleTrigger(node, (StreamEvent) event, pid);
			return;
		} 
		else if (event instanceof VideoMessage) 
		{
			VideoMessage eventMsg = (VideoMessage) event;

			if (m_streamsISubscribeTo.size() == 0) {
				// System.err.println("WTF Man brrr...");
				MessageStatistics.droppedNode(m_myStreamNodeId);
				MessageStatistics.droppedStream(eventMsg.streamId);
				return;
			}

			if (eventMsg.streamId >= 0 && m_streamsISubscribeTo.containsKey((eventMsg.streamId)))
				m_streamManager.processVideoMessage(node, eventMsg, pid);
			else if (eventMsg.streamId == -1) 
				MessageStatistics.latencyNode(eventMsg.source, CommonState.getTime()-eventMsg.sent);
				
		} 
		else if (event instanceof VideoPublishEvent) 
		{
			m_streamManager.streamVideo(node, (VideoPublishEvent) event, pid);
		} 
		else if (event instanceof VideoTransportEvent) 
		{
			m_transportControl.transportMessages(node, pid);
		}
		else if (event instanceof StreamMessage) 
		{
			handleMessage(node, (StreamMessage) event, pid);
			return;
		}
		else if (event instanceof TurbulenceEvent) {
			m_turbulenceManager.sendTurbulence(node, (TurbulenceEvent)event, pid);
		}
		else 
		{
			System.err.println("Unknown message!");
		}
	}

	@Override
	public Object clone() {
		StreamThing s = null;
		try {
			s = (StreamThing) super.clone();
			s.m_streamManager = null;
			s.m_turbulenceManager = null;
			s.m_transportControl = null;
			s.m_myStreamNodeId = -1;
			s.m_streamsISubscribeTo = new HashMap<Integer, Integer>();
			s.m_latestPing = new HashMap<Integer, List<Integer> >();
			s.m_streamsIPublish = new ArrayList<Integer> ();
			
		} catch (CloneNotSupportedException e) {
			e.printStackTrace();
		}
        return s;
	}

	/* protocol methods */
	private void handleMessage(Node src, StreamMessage msg, int pid) {
		Transport transport = (Transport) src.getProtocol(FastConfig
				.getTransport(pid));
		
		switch (msg.type) {
		case SUBSCRIBE:
			//System.out.println("got subscribe from " + msg.source + " sending ack with rate");
			StreamMessage replyAck = new StreamMessage(MessageType.SUBSCRIBE_ACK, GetStreamIdFromNodeId(src.getID()));
			replyAck.streamRate = 500; // no use for this... 
			replyAck.streamId = msg.streamId;
			Node dest = GetNodeFromStreamId(msg.source);
			transport.send(src, dest, replyAck, pid);
			break;
		case SUBSCRIBE_ACK:
			// Join the multicast tree
			
			NodeWorld nwToJoin = m_videoStreamIdToMulticastTreeMap.get (msg.streamId);	
			nwToJoin.AddNode(m_myStreamNodeId, NodeConfig.GetUploadCapacityForNode(m_myStreamNodeId));

			// in case I'm not a publisher (most likely), I need a stream manager to be able to forward data
			if (m_streamManager == null) {
				Float fu = NodeConfig.GetUploadCapacityForNode(m_myStreamNodeId);
				if (fu == null) {
					fu= new Float(5000);
				}
				m_streamManager = new StreamManager(m_transportControl, fu.intValue());
				MessageStatistics.bandwidth(m_myStreamNodeId, TotalAmountOfUpload());
			}
			
			//System.out.println(m_myStreamNodeId +" Imma subscribe to : " + msg.streamId + " with rate " + msg.streamRate);
			m_streamsISubscribeTo.put(msg.streamId, msg.streamRate);
			
			break;
		case LEAVE:
			
			// update node world
			break;
		case PING:
			//System.out.println(m_myStreamNodeId + " received a ping from " + msg.source);
			StreamMessage pong = new StreamMessage(MessageType.PONG, GetStreamIdFromNodeId(src.getID()));
			pong.streamId = msg.streamId;
			Node n = GetNodeFromStreamId(msg.source);
			if(n != null)
			{
				transport.send(src, n, pong, pid);
			}
			break;
		case PONG:
			//System.out.println(m_myStreamNodeId + " received a pong from " + msg.source);
			List<Integer> vect = m_latestPing.get(msg.streamId);
			
			for (int i = 0; i < vect.size (); i++)
			{
				if (vect.get(i) == msg.source)
				{
					vect.remove (i);
					break;
				}
			}
				
			break;
		}

	}

	/** 
	 * Handle simulation events based on those specified in input file
	 */
	private void handleTrigger(Node src, StreamEvent msg, int pid) {
		Transport transport = (Transport) src.getProtocol(FastConfig
				.getTransport(pid));
		//Debug.info(src.getID() + "Parsing msg: " + msg.toString());
				
		switch (msg.GetEventType()) {
		case JOIN:
			// update stream ID
			// update global map of stream node id to node id
			m_myStreamNodeId = msg.GetNodeId();
			StreamThing.m_streamIdToNodeId.put (m_myStreamNodeId,  src.getID());
			
			m_transportControl = new TransportControl(NodeConfig.GetUploadCapacityForNode(m_myStreamNodeId).intValue());
			
			break;
		case LEAVE:
			// inform children/parent
			// (remove from required trees)
			//
			
			Iterator<Entry<Integer, Integer>> iter = m_streamsISubscribeTo.entrySet().iterator();
			while (iter.hasNext()) {
				Entry<Integer, Integer> entry = iter.next();
				m_videoStreamIdToMulticastTreeMap.get (entry.getKey()).RemoveNodeGraceful(m_myStreamNodeId);
			}
			m_streamIdToNodeId.remove(m_myStreamNodeId);
			m_streamsISubscribeTo.clear ();
			
			//System.out.println("I actually enter this place");
			//m_streamIdToNodeId.remove(m_myStreamNodeId);
			break;
		case PUBLISH:
			// Add to video stream to streamNodeId map
			m_videoStreamToStreamNodeId.put (msg.GetEventParams().get(0).intValue(), m_myStreamNodeId);	
			
			// Create new multicast tree
			System.out.println(m_myStreamNodeId + " is going to publish ");
			Float f = NodeConfig.GetUploadCapacityForNode(m_myStreamNodeId);
			if (f == null) {
				f = new Float(5000);
			}
			NodeWorld nw = new NodeWorld (msg.GetEventParams().get (0).intValue(), m_myStreamNodeId, f.intValue(), msg.GetEventParams().get (2).intValue());
			m_videoStreamIdToMulticastTreeMap.put (msg.GetEventParams().get (0).intValue(), nw);
			
			m_uploadBandwidthOfStreamsIPublish += msg.GetEventParams().get(2).intValue();
			// I am now the root of a multicast tree;
			
			// start streaming
			if (m_streamManager == null) {
				m_streamManager = new StreamManager(m_transportControl, f.intValue());
			}
			m_streamManager.publishNewStream(msg);
			m_streamManager.startStream(src, pid, msg.GetEventParams().get(0).intValue());
			
			m_streamsIPublish.add(msg.GetEventParams().get(0).intValue());
			
			// update stats
			MessageStatistics.bandwidth(m_myStreamNodeId, TotalAmountOfUpload());
			
			break;
		case SUBSCRIBE:
			/* send subscribe msg to publisher */
			StreamMessage subscribeMessage = new StreamMessage(MessageType.SUBSCRIBE, msg.GetNodeId());
			subscribeMessage.streamId = msg.GetEventParams().get(0).intValue();
			Node dest = GetNodeFromStreamId(m_videoStreamToStreamNodeId.get(msg.GetEventParams().get(0).intValue()));
			if (dest != null)
			{
				transport.send(src, dest, subscribeMessage, pid);
			}
			break;
		case UNSUBSCRIBE:
			// do unsubscribe
			
			// Remove from multicast tree
			
			m_videoStreamIdToMulticastTreeMap.get (msg.GetEventParams().get(0).intValue()).RemoveNodeGraceful(m_myStreamNodeId);
			//m_streamIdToNodeId.remove(m_myStreamNodeId);
			m_streamsISubscribeTo.remove((Object) msg.GetEventParams().get(0).intValue());
			// Notify StreamManager
			break;
		case TURBULENCE:
			
			if (m_turbulenceManager == null) {
				m_turbulenceManager = new TurbulenceManager(m_transportControl, NodeConfig.GetUploadCapacityForNode(m_myStreamNodeId).intValue());
			}
			
			m_turbulenceManager.startTurbulence(src, msg.GetEventParams().get(0).intValue(), 
					msg.GetEventParams().get(1).intValue(), 
					msg.GetEventParams().get(2).intValue(),
					msg.GetEventParams().get(3).intValue(), pid);
			
			break;
		case TIMEOUT:
			
			// check existing msgs

			// System.out.println("pingmap" + m_latestPing.size());
			Iterator<Entry<Integer, List<Integer> >> boo = m_latestPing.entrySet().iterator();
			
			while(boo.hasNext()){
				Entry<Integer, List<Integer> > entry = boo.next();
				List<Integer> vect = entry.getValue();
				
				for (int i = 0; i < vect.size(); i++)
				{
					//System.out.println(m_myStreamNodeId + " Me no receive pong from " + vect.get(i) + " :(");
					m_videoStreamIdToMulticastTreeMap.get (entry.getKey()).RemoveNodeGraceful(vect.get(i));
				}
			}
			
			m_latestPing.clear();
			
			// send ping msgs
			
			if (!m_streamsIPublish.isEmpty ()){
				Iterator<Integer> streams = m_streamsIPublish.iterator();
				while (streams.hasNext()) {
					int stream = streams.next();
					
					// ping children
					//m_latestPing.put(stream, m_videoStreamIdToMulticastTreeMap.get(stream).GetChildren(m_myStreamNodeId));
					//System.out.println(m_myStreamNodeId + " is pinging " + m_videoStreamIdToMulticastTreeMap.get(stream).GetChildren(m_myStreamNodeId).size () + " kids");
					List<Integer> vect = m_videoStreamIdToMulticastTreeMap.get (stream).GetChildren (m_myStreamNodeId);
					
					
					List<Integer> blah = new ArrayList<Integer>();
					if (vect != null)
					{
						for (int i = 0; i < vect.size (); i++)
						{
							int k = vect.get(i);
							blah.add (k);
						}
						
						m_latestPing.put(stream, blah);
						
						for (int childId : m_videoStreamIdToMulticastTreeMap.get(stream).GetChildren(m_myStreamNodeId)) {
							Node child = GetNodeFromStreamId(childId);
							if (child != null)
							{
								StreamMessage ping = new StreamMessage(MessageType.PING, GetStreamIdFromNodeId(src.getID()));
								ping.streamId = stream;
								transport.send(src, child, ping, pid);
							}
						}
					}
				}
			}
			
			if (!m_streamsISubscribeTo.isEmpty()) {
				
				Iterator<Entry<Integer, Integer>> streams = m_streamsISubscribeTo.entrySet().iterator();
				while (streams.hasNext()) {
					Entry<Integer, Integer> stream = streams.next();
					
					// ping children
					//System.out.println(m_myStreamNodeId + " is pinging " + m_videoStreamIdToMulticastTreeMap.get(stream.getKey()).GetChildren(m_myStreamNodeId).size () + " kids");
					List<Integer> vect = m_videoStreamIdToMulticastTreeMap.get (stream.getKey ()).GetChildren (m_myStreamNodeId);
					List<Integer> blah = new ArrayList<Integer>();
					
					if (vect !=null)
					{
						for (int i = 0; i < vect.size (); i++)
						{
							int k = vect.get(i);
							blah.add (k);
						}
					}
					
					Integer p = m_videoStreamIdToMulticastTreeMap.get(stream.getKey()).GetParent(m_myStreamNodeId);
					if (p != null)	
					{
						blah.add (p);
					}
					
					m_latestPing.put(stream.getKey(), blah);
					
					// First ping parent
					Node pa = null;
					if (p != null)
					{
					 pa = GetNodeFromStreamId (p);
					}
					
					if (pa != null)
					{
					//System.out.println("Children: " + vect.get(i));
					StreamMessage ping = new StreamMessage(MessageType.PING, GetStreamIdFromNodeId(src.getID()));
					ping.streamId = stream.getKey();
					transport.send(src, pa, ping, pid);
					}
					
					if (vect != null)
					{
					for (int i = 0; i < vect.size (); i++)
					{
						Node n = GetNodeFromStreamId (vect.get(i));
						
						if (n != null)
						{
						//System.out.println("Children: " + vect.get(i));
						StreamMessage ping = new StreamMessage(MessageType.PING, GetStreamIdFromNodeId(src.getID()));
						ping.streamId = stream.getKey();
						transport.send(src, GetNodeFromStreamId(vect.get(i)), ping, pid);
						}
					}
					}
				}
			}
			break;
		default:
			break;
		}


	}
	
	public static Node GetNodeFromStreamId(int streamId) {
		if (m_streamIdToNodeId.containsKey(streamId))
			return GetNodeFromNodeId(m_streamIdToNodeId.get(streamId));
		else
			return null;
	}
	
}
