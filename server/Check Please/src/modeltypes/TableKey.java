package modeltypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.json.JSONException;
import org.json.JSONObject;

import utils.DSConverter;
import utils.Frac;
import utils.HttpErrMsg;
import utils.MyUtils;
import utils.TicketItem;

import com.google.appengine.api.channel.ChannelFailureException;
import com.google.appengine.api.channel.ChannelMessage;
import com.google.appengine.api.channel.ChannelService;
import com.google.appengine.api.channel.ChannelServiceFactory;
import com.google.appengine.api.datastore.AsyncDatastoreService;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Transaction;

public class TableKey extends AbstractModelType
{
	protected String kindName() { return getKind(); }
	public static String getKind() { return "table_key"; }
	
	public static enum ConnectionStatus {PROCESSING, LEFT_WHILE_PROCESSING, PAID, INPUTTING, CLOSED};
	private static ConnectionStatus parseCS(Long status)
	{
		if(status == null)
			return ConnectionStatus.CLOSED;
		else
			return ConnectionStatus.values()[status.intValue()];
	}
	private static boolean beingPaid(ConnectionStatus status)
	{
		return (status == ConnectionStatus.PROCESSING) || (status == ConnectionStatus.LEFT_WHILE_PROCESSING) || (status == ConnectionStatus.PAID);
	}

	private String restr;
	private String query;//How to find what's on this table's ticket

	/*	A map from client IDs to connection statuses.  If an connection ID is not in the map, it is
	 *	either closed or never opened
	 */
	private Map<String, Long> connectionStatus;
	private Map<String, Long> connectionExpr;
	private Set<String> deadConnections;

	/*	Items, when split between multiple users, are split into parts.  Each part contains the
	 *	following information:
	 *		num -	A number identifying the part.  Always a non-negative integer.  Part numbers are not
	 *				reused for the lifetime of the item
	 *		owner - The connection ID of the user who owns that part
	 *		frac -	The fraction of the item which this part contains.  Can be null.  If some fraction
	 *				are null, then amount of the item which is unaccounted for is divided evenly between
	 *				the null fractions
	 */
	private Map<String, List<Long>> itemNums;
	private Map<String, List<String>> itemOwners;
	private Map<String, List<Frac>> itemFracs;

	private static List<Long> defaultNums = Arrays.asList((Long)0L);
	private static List<String> defaultOwners = Arrays.asList((String)null);
	private static List<Frac> defaultFracs = Arrays.asList((Frac)null);

	
	public TableKey(Key k, DatastoreService ds) throws EntityNotFoundException { super(k, ds); }
	public TableKey(Entity e) { super(e); }

	public TableKey(Key k, String restr, String query)
	{
		setKey(k);
		this.restr = restr;
		this.query = query;
		deadConnections = new HashSet<String>();
		connectionStatus = new HashMap<String, Long>();
		connectionExpr = new HashMap<String, Long>();
		itemFracs = new HashMap<String, List<Frac>>();
		itemNums = new HashMap<String, List<Long>>();
		itemOwners = new HashMap<String, List<String>>();
	}

	//-------------------
	//| PRIVATE METHODS |
	//-------------------
	private void closeDeadConnections()
	{
		AsyncDatastoreService ds = DatastoreServiceFactory.getAsyncDatastoreService();
		Iterator<String> iter = deadConnections.iterator();
		while(iter.hasNext()) {
			ds.delete((Transaction)null, KeyFactory.createKey(ConnectionToTablePointer.getKind(), iter.next()));
			iter.remove();
		}
	}
	private void mySetConnectionStatus(String connectionID, ConnectionStatus status, boolean force)
	{
		ConnectionStatus oldStatus = parseCS(connectionStatus.get(connectionID));
		switch(oldStatus) {
			case INPUTTING: break;
			case PROCESSING:
				if(status == ConnectionStatus.CLOSED)
					status = ConnectionStatus.LEFT_WHILE_PROCESSING;
				break;
			case LEFT_WHILE_PROCESSING: switch(status) {
					case CLOSED:
						if(force)
							break;
					case PROCESSING:
						status = ConnectionStatus.LEFT_WHILE_PROCESSING;
						break;
					case INPUTTING:
						status = ConnectionStatus.CLOSED;
						break;
					default:
						break;
				} break;
			case PAID:
				if(!((status == ConnectionStatus.CLOSED) && force))
					status = ConnectionStatus.PAID;
				break;
			case CLOSED: status = ConnectionStatus.CLOSED; break;
		}

		//Ignore redundant updates
		if(oldStatus == status)
			return;

		//Clear fractions
		if((status == ConnectionStatus.INPUTTING) || (status == ConnectionStatus.CLOSED))
			for(String id : itemOwners.keySet()) {
				List<String> owners =  itemOwners.get(id);
				for(int i = 0; i < owners.size(); i++)
					if(connectionID.equals(owners.get(i)))
						itemFracs.get(id).set(i, null);
			}

		//Actually set the status
		if(status == ConnectionStatus.CLOSED) {
			deadConnections.add(connectionID);
			connectionStatus.remove(connectionID);
			connectionExpr.remove(connectionID);
		} else {
			connectionStatus.put(connectionID, new Long(status.ordinal()));
			
			//Set fractions
			if(oldStatus == ConnectionStatus.INPUTTING)
				for(String id : itemFracs.keySet()) {
					List<Frac> fracs = itemFracs.get(id);
					Frac fillIn = Frac.ONE;
					Long denom = 0L;
					for(Frac f : fracs)
						if(f == null)
							denom++;
						else
							fillIn = fillIn.sub(f);
					if(denom > 0L) {
						fillIn = fillIn.div(denom);
						List<String> owners =  itemOwners.get(id);
						for(int i = 0; i < owners.size(); i++)
							if(connectionID.equals(owners.get(i)) && (fracs.get(i) == null))
								fracs.set(i, fillIn);
					}
				}
		}
	}
	/*	Stores information on an item which can be used to later create the JSON object for the ticket
	 *	for any connection ID
	 */
	private class ItemJSONInfo {
		public JSONObject json;
		public String id;
		public String owner;
		public ItemJSONInfo(JSONObject json, String id, String owner) {
			this.json = json;
			this.id = id;
			this.owner = owner;
		}
	}

	/*	Combine ticket item information with the metadata in the table key to create the bases of the
	 * JSON object for the ticket to send to a user
	 *
	 *	@see /webprojects/app/_js/models.js for a description of what the JSON object needs to look like
	 */
	private List<ItemJSONInfo> preprocessItems(List<TicketItem> items) throws JSONException
	{
		List<ItemJSONInfo> ret = new ArrayList<ItemJSONInfo>();
		for(TicketItem item : items) {
			String itemID = item.getID();
			Frac fillerFrac = Frac.ONE;
			Long denom = 0L;
			List<Frac> fracs = itemFracs.get(itemID);
			if(fracs == null)
				fracs = defaultFracs;
			for(int i = 0; i < fracs.size(); i++) {
				Frac f = fracs.get(i);
				if(f == null)
					denom++;
				else
					fillerFrac = fillerFrac.sub(f);
			}
			if(denom != 0L)
				fillerFrac = fillerFrac.div(denom);
			List<Long> nums = itemNums.get(itemID);
			if(nums == null)
				nums = defaultNums;
			List<String> owners = itemOwners.get(itemID);
			if(owners == null)
				owners = defaultOwners;
			for(int i = 0; i < fracs.size(); i++) {
				Frac frac = fracs.get(i);
				if(frac == null)
					frac = fillerFrac;
				JSONObject json = item.toJSON();
				json.put("num", frac.getNum());
				json.put("denom", frac.getDenom());
				ret.add(new ItemJSONInfo(json, itemID+":"+nums.get(i), owners.get(i)));
			}
		}
		return ret;
	}
	private void updateListeners(List<TicketItem> items) throws JSONException
	{
		updateListeners(items, null);
	}

	private void updateListeners(List<TicketItem> items, String connectionID) throws JSONException
	{
		ChannelService channelService = ChannelServiceFactory.getChannelService();
		List<ItemJSONInfo> ppItems = preprocessItems(items);
		for(String cID : connectionStatus.keySet()) try {
			if(!cID.equals(connectionID))
				channelService.sendMessage(new ChannelMessage(cID, "SET_ITEMS\n"+myGetItemsJSON(ppItems, cID)));
		} catch(ChannelFailureException e) {}
	}


	private JSONObject myGetItemsJSON(List<ItemJSONInfo> items, String connectionID) throws JSONException {
		JSONObject ret = new JSONObject();
		for(int i = 0; i < items.size(); i++) {
			ItemJSONInfo item = items.get(i);
			if(connectionID.equals(item.owner))
				item.json.put("status", 0);
			else if(item.owner == null)
				item.json.put("status", 1);
			else {
				ConnectionStatus status = parseCS(connectionStatus.get(item.owner));
				if(beingPaid(status))
					item.json.put("status", 3);
				else if(status == ConnectionStatus.CLOSED)
					item.json.put("status", 1);
				else
					item.json.put("status", 2);
			}
			ret.put(item.id, item.json);
		}
		return ret;
	}

	//---------------------
	//| ACCESSORS METHODS |
	//---------------------
	public String getRestrUsername()
	{
		return restr;
	}

	public Restaurant getRestr(DatastoreService ds)
	{
		return new Restaurant(MyUtils.get_NoFail(KeyFactory.createKey(Restaurant.getKind(), restr), ds));
	}

	public String getQuery() {
		return query;
	}

	public Map<String, Frac> getPayFracs(String connectionID) {
		Map<String, Frac> ret = new HashMap<String, Frac>();
		for(String id : itemFracs.keySet()) {
			Frac unaccounted = Frac.ONE;
			Frac userAmount = Frac.ZERO;
			int num = 0;
			int denom = 0;
			List<String> owners = itemOwners.get(id);
			List<Frac> fracs = itemFracs.get(id);
			for(int i = 0; i < fracs.size(); i++) {
				Frac f = fracs.get(i);
				if(connectionID.equals(owners.get(i))) {
					if(f == null)
						num++;
					else
						userAmount = userAmount.add(f);
				};
				if(f == null) {
					denom++;
				} else {
					unaccounted = unaccounted.sub(f);
				}
			}
			if(denom > 0)
				userAmount = userAmount.add(unaccounted.mult(new Frac(num, denom)));
			ret.put(id, userAmount);
		}
		return ret;
	}

	/**	Figures out how much some connection needs to pay (not including tip)
	 *
	 *	@param	items The items on the ticket
	 *	@param	connectionID The person to measure the payment of
	 *	@return	The total to pay in cents
	 */
	public long getTotalToPay(List<TicketItem> items, String connectionID) throws HttpErrMsg {
		long total = 0L;
		Map<String, Frac> payFracs = getPayFracs(connectionID);
		for(TicketItem item : items) {
			Frac f = payFracs.get(item.getID());
			if(f == null)
				throw new HttpErrMsg("No metadata for item with ID \""+item.getID()+'"');
			total += f.mult(item.getNetPrice()).ceil();
		}
		return new Frac(total, 100L).ceil();
	}

	public boolean isPaid() {
		//Find everyone who owns part of an item
		Set<String> allOwners = new HashSet<String>();
		for(String id : itemOwners.keySet()) {
			List<String> owners = itemOwners.get(id);
			List<Frac> fracs = itemFracs.get(id);
	
			Frac unaccounted = Frac.ONE;
			for(Frac f : fracs)
				unaccounted = unaccounted.sub(f == null ? Frac.ZERO : f);
			boolean nothingUnaccounted = Frac.ZERO.equals(unaccounted);

			//Go through all the owners and add the ones who own something
			for(int i = 0; i < owners.size(); i++) {
				Frac f = fracs.get(i);
				if(!Frac.ZERO.equals(f) && ((f != null) || !nothingUnaccounted)) {
					if(owners.get(i) != null)
						allOwners.add(owners.get(i));
					else
						return false;
				}
			}
		}
		//Check that all those owners are paid
		for(String owner : allOwners)
			if(parseCS(connectionStatus.get(owner)) != ConnectionStatus.PAID)
				return false;
		return true;
	}

	public JSONObject getItemsJSON(List<TicketItem> items, String connectionID) throws JSONException {
		return myGetItemsJSON(preprocessItems(items), connectionID);
	}

	//--------------------
	//| MUTATORS METHODS |
	//--------------------
	public void clearMetadata()
	{
		closeDeadConnections();

		ChannelService channelService = ChannelServiceFactory.getChannelService();
		for(String cID : connectionStatus.keySet())
			channelService.sendMessage(new ChannelMessage(cID, isPaid() ? "PAID" : "CLEARED"));


		deadConnections.addAll(connectionStatus.keySet());
		connectionStatus.clear();
		connectionExpr.clear();
		itemFracs.clear();
		itemNums.clear();
		itemOwners.clear();
	}
	private boolean shouldClose(String cID, boolean current, ChannelService channelService, String uuid)
	{
		ConnectionStatus status = parseCS(connectionStatus.get(cID));
		if(status == ConnectionStatus.CLOSED)
			return false;//Already closed
		else if((status == ConnectionStatus.PAID) || (status == ConnectionStatus.LEFT_WHILE_PROCESSING))
			return !current;

		return false;
		
		//TODO use heartbeats
	}
	public boolean initMetadata(List<TicketItem> items) throws JSONException
	{
		closeDeadConnections();

		//First remove expired connections
		long time = new Date().getTime();
		for(String cID : new HashSet<String>(connectionExpr.keySet()))
			if(time > connectionExpr.get(cID))
				mySetConnectionStatus(cID, ConnectionStatus.CLOSED, false);

		//Figure out what's we should have on the ticket
		Set<String> currentItems = new HashSet<String>();
		for(TicketItem item : items)
			currentItems.add(item.getID());

		//Compare that to what's currently being tracked, and figure out what connections own
		//current items vs ones no longer on the ticket
		Set<String> itemsAlreadyTracked = new HashSet<String>();
		Set<String> itemsToUntrack = new HashSet<String>();
		Set<String> currentConnections = new HashSet<String>();
		for(String itemID : itemOwners.keySet())
			if(currentItems.contains(itemID)) {
				for(String connectionID : itemOwners.get(itemID))
					if(connectionID != null)
						currentConnections.add(connectionID);
				itemsAlreadyTracked.add(itemID);
			} else {
				itemsToUntrack.add(itemID);
			}

		//Figure out what we need to add
		Set<String> itemsToTrack = new HashSet<String>();
		for(TicketItem item : items)
			if(!itemsAlreadyTracked.contains(item.getID()))
				itemsToTrack.add(item.getID());

		//Figure out what connections need to be closed
		Set<String> connectionsToClose = new HashSet<String>();
		ChannelService channelService = ChannelServiceFactory.getChannelService();
		String uuid = UUID.randomUUID().toString();
		for(String cID : connectionStatus.keySet())
			if(shouldClose(cID, currentConnections.contains(cID), channelService, uuid))
				connectionsToClose.add(cID);

		//Remove shit, add shit, do shit
		for(String cID : connectionsToClose)
			mySetConnectionStatus(cID, ConnectionStatus.CLOSED, true);
		for(String item : itemsToUntrack) {
			itemFracs.remove(item);
			itemNums.remove(item);
			itemOwners.remove(item);
		}
		for(String item : itemsToTrack) {
			itemFracs.put(item, new ArrayList<Frac>(defaultFracs));
			itemNums.put(item, new ArrayList<Long>(defaultNums));
			itemOwners.put(item, new ArrayList<String>(defaultOwners));
		}

		//Sync all connections and return bool showing if commit needed
		if(itemsToUntrack.size() > 0 || itemsToTrack.size() > 0 || connectionsToClose.size() > 0) {
			updateListeners(items);
			return true;
		} else
			return false;
	}
	public void newConnection(String connectionID, String platform, List<TicketItem> items, DatastoreService ds) throws JSONException
	{
		connectionStatus.put(connectionID, new Long(ConnectionStatus.INPUTTING.ordinal()));
		connectionExpr.put(connectionID, new Date().getTime()+2*60*60*1000);
		for(String itemID : itemOwners.keySet()) {
			List<String> owners = itemOwners.get(itemID);
			for(int i = 0; i < owners.size(); i++) {
				String owner = owners.get(i);
				if((owner == null) || (parseCS(connectionStatus.get(owner)) == ConnectionStatus.CLOSED))
					owners.set(i, connectionID);
			}
		}
		new ConnectionToTablePointer(KeyFactory.createKey(ConnectionToTablePointer.getKind(), connectionID), getKey().getName()).commit(ds);
		updateListeners(items, connectionID);
	}
	public void setConnectionStatus(String connectionID, ConnectionStatus status, List<TicketItem> items, DatastoreService ds) throws JSONException
	{
		mySetConnectionStatus(connectionID, status, false);
		updateListeners(items, connectionID);
	}
	public void setOwner(String id, String connectionID, List<TicketItem> items, DatastoreService ds) throws JSONException, HttpErrMsg {
		if(connectionID != null) {
			ConnectionStatus status = parseCS(connectionStatus.get(connectionID));
			if(status != ConnectionStatus.INPUTTING)
				throw new HttpErrMsg("Cannot add items to this connection");
		}
		int sep = id.lastIndexOf(':');
		Long num = Long.parseLong(id.substring(sep+1));
		id = id.substring(0, sep);
		Integer index = null;
		List<Long> nums = itemNums.get(id);
		if(nums == null)
			throw new HttpErrMsg("No such ticket item");
		for(int i = 0; i < nums.size() && index == null; i++)
			if(nums.get(i) == num)
				index = i;
		if(index == null)
			throw new HttpErrMsg("Invalid split information");
		if(beingPaid(parseCS(connectionStatus.get(itemOwners.get(id).get(index)))))
			throw new HttpErrMsg("Current owner cannot give up this item");
		itemOwners.get(id).set(index, connectionID);
		updateListeners(items, connectionID);
	}

	public void checkAll(String connectionID, List<TicketItem> items, DatastoreService ds) throws JSONException, HttpErrMsg {
		ConnectionStatus status = parseCS(connectionStatus.get(connectionID));
		if(status != ConnectionStatus.INPUTTING)
			throw new HttpErrMsg("Cannot add items to this connection");
		for(String id : itemOwners.keySet()) {
			List<String> owners = itemOwners.get(id);
			for(int i = 0; i < owners.size(); i++) {
				String owner = owners.get(i);
				if((owner == null) || (connectionStatus.get(owner) == null))
					owners.set(i, connectionID);
			}
		}
		updateListeners(items, connectionID);
	}

	public void uncheckAll(String connectionID, List<TicketItem> items, DatastoreService ds) throws JSONException, HttpErrMsg {
		ConnectionStatus status = parseCS(connectionStatus.get(connectionID));
		if(status != ConnectionStatus.INPUTTING)
			throw new HttpErrMsg("Cannot remove items from this connection");
		for(String id : itemOwners.keySet()) {
			List<String> owners = itemOwners.get(id);
			for(int i = 0; i < owners.size(); i++)
				if(connectionID.equals(owners.get(i)))
					owners.set(i, null);
		}
		updateListeners(items, connectionID);
	}

	public void split(String id, Long nWays, String connectionID, List<TicketItem> items, DatastoreService ds) throws JSONException, HttpErrMsg {
		ConnectionStatus status = parseCS(connectionStatus.get(connectionID));
		if(status != ConnectionStatus.INPUTTING)
			throw new HttpErrMsg("Cannot split items this connection");
		List<Integer> myParts = new ArrayList<Integer>();
		List<Integer> theirParts = new ArrayList<Integer>();
		List<Integer> unownedParts = new ArrayList<Integer>();

		List<String> owners = itemOwners.get(id);
		if(owners == null)
			throw new HttpErrMsg("No such item to split");
		for(int i = 0; i < owners.size(); i++) {
			String owner = owners.get(i);
			if(connectionID.equals(owner))
				myParts.add(i);
			else if((owner == null) || (connectionStatus.get(owner) == null))
				unownedParts.add(i);
			else if(parseCS(connectionStatus.get(owner)) == ConnectionStatus.INPUTTING)
				theirParts.add(i);
		}

		long diff = nWays - myParts.size() - theirParts.size() - unownedParts.size();
		if(diff == 0)
			return;

		List<Frac> fracs = itemFracs.get(id);
		List<Long> nums = itemNums.get(id);
		if(diff > 0) {
			Long maxNum = Collections.max(nums);
			while(diff > 0) {
				owners.add(null);
				fracs.add(null);
				nums.add(++maxNum);
				diff--;
			}
		} else while(diff < 0) {
			int index;
			if(unownedParts.size() > 0)
				index = unownedParts.remove(unownedParts.size()-1);
			else if(theirParts.size() > 0)
				index = theirParts.remove(theirParts.size()-1);
			else
				index = myParts.remove(myParts.size()-1);
			owners.remove(index);
			fracs.remove(index);
			nums.remove(index);
			diff++;
		}
		updateListeners(items, connectionID);
	}

	//--------------------------
	//| to/from ENTITY METHODS |
	//--------------------------

	public Entity toEntity()
	{
		Entity e = new Entity(getKey());
		e.setProperty("restr", restr);
		e.setProperty("query", query);
		DSConverter.set(e, "connectionsToClose", deadConnections, DSConverter.DataTypes.SET);
		DSConverter.set(e, "connectionStatus", connectionStatus, DSConverter.DataTypes.MAP);
		DSConverter.set(e, "connectionExpr", connectionExpr, DSConverter.DataTypes.MAP);
		DSConverter.set(e, "itemFracs", itemFracs, DSConverter.DataTypes.MAP, DSConverter.DataTypes.LIST, DSConverter.DataTypes.FRAC);
		DSConverter.set(e, "itemNums", itemNums, DSConverter.DataTypes.MAP, DSConverter.DataTypes.LIST);
		DSConverter.set(e, "itemOwners", itemOwners, DSConverter.DataTypes.MAP, DSConverter.DataTypes.LIST);
		return e;
	}

	@SuppressWarnings("unchecked")
	public void fromEntity(Entity e)
	{
		restr = (String) e.getProperty("restr");
		query = (String) e.getProperty("query");
		deadConnections = (Set<String>) DSConverter.get(e, "connectionsToClose", DSConverter.DataTypes.SET);
		connectionStatus = (Map<String, Long>) DSConverter.get(e, "connectionStatus", DSConverter.DataTypes.MAP);
		connectionExpr = (Map<String, Long>) DSConverter.get(e, "connectionExpr", DSConverter.DataTypes.MAP);
		itemFracs = (Map<String, List<Frac>>) DSConverter.get(e, "itemFracs", DSConverter.DataTypes.MAP, DSConverter.DataTypes.LIST, DSConverter.DataTypes.FRAC);
		itemNums = (Map<String, List<Long>>) DSConverter.get(e, "itemNums", DSConverter.DataTypes.MAP, DSConverter.DataTypes.LIST);
		itemOwners = (Map<String, List<String>>) DSConverter.get(e, "itemOwners", DSConverter.DataTypes.MAP, DSConverter.DataTypes.LIST);
	}
}
