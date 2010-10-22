/**
 * openHAB, the open Home Automation Bus.
 * Copyright (C) 2010, openHAB.org <admin@openhab.org>
 *
 * See the contributors.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 * Additional permission under GNU GPL version 3 section 7
 *
 * If you modify this Program, or any covered work, by linking or
 * combining it with Eclipse (or a modified version of that library),
 * containing parts covered by the terms of the Eclipse Public License
 * (EPL), the licensors of this Program grant you additional permission
 * to convey the resulting work.
 */

package org.openhab.binding.knx.internal.bus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openhab.binding.knx.config.KNXBindingChangeListener;
import org.openhab.binding.knx.config.KNXBindingProvider;
import org.openhab.binding.knx.config.KNXTypeMapper;
import org.openhab.binding.knx.internal.connection.KNXConnection;
import org.openhab.core.events.AbstractEventSubscriber;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.Type;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tuwien.auto.calimero.DetachEvent;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.datapoint.CommandDP;
import tuwien.auto.calimero.datapoint.Datapoint;
import tuwien.auto.calimero.datapoint.StateDP;
import tuwien.auto.calimero.dptxlator.DPTXlator;
import tuwien.auto.calimero.dptxlator.TranslatorTypes;
import tuwien.auto.calimero.exception.KNXException;
import tuwien.auto.calimero.exception.KNXIllegalArgumentException;
import tuwien.auto.calimero.process.ProcessCommunicator;
import tuwien.auto.calimero.process.ProcessEvent;
import tuwien.auto.calimero.process.ProcessListener;

/**
 * This is the central class that takes care of the event exchange between openHAB and KNX.
 * It is fully connected (read and write) to the openHAB event bus and also has write access
 * to KNX while as well listening for incoming KNX messages.
 * 
 * The received messages are converted into the right format for the other bus and published 
 * to it.
 * 
 * @author Kai Kreuzer
 * @since 0.3.0
 *
 */
public class KNXBinding extends AbstractEventSubscriber implements ProcessListener, KNXBindingChangeListener {

	private static final Logger logger = LoggerFactory.getLogger(KNXBinding.class);

	/** to keep track of all KNX binding providers */
	protected Collection<KNXBindingProvider> providers = new HashSet<KNXBindingProvider>();

	/** to keep track of all KNX type mappers */
	protected Collection<KNXTypeMapper> typeMappers = new HashSet<KNXTypeMapper>();

	private EventPublisher eventPublisher;

	/**
	 * used to store events that we have sent ourselves; we need to remember them for not reacting to them
	 */
	private List<String> ignoreEventList = new ArrayList<String>();

	/**
	 * to keep track of all datapoints for which we should send a read request to the KNX bus
	 */
	private Set<Datapoint> datapointsToInitialize = Collections.synchronizedSet(new HashSet<Datapoint>());

	/** the datapoint initializer, which runs in a separate thread */
	private DatapointInitializer initializer = new DatapointInitializer();

	public void activate(ComponentContext componentContext) {
		initializer.start();
	}

	public void deactivate(ComponentContext componentContext) {
		for(KNXBindingProvider provider : providers) {
			provider.removeBindingChangeListener(this);
		}
		providers.clear();
		initializer.setInterrupted(true);
	}

	public void setEventPublisher(EventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
	}

	public void unsetEventPublisher(EventPublisher eventPublisher) {
		this.eventPublisher = null;
	}

	public void addKNXBindingProvider(KNXBindingProvider provider) {
		this.providers.add(provider);
		provider.addBindingChangeListener(this);
		allBindingsChanged(provider);
	}

	public void removeKNXBindingProvider(KNXBindingProvider provider) {
		this.providers.remove(provider);
		provider.removeBindingChangeListener(this);
	}

	public void addKNXTypeMapper(KNXTypeMapper typeMapper) {
		this.typeMappers.add(typeMapper);
	}

	public void removeKNXTypeMapper(KNXTypeMapper typeMapper) {
		this.typeMappers.remove(typeMapper);
	}

	@Override
	public void receiveCommand(String itemName, Command command) {
		if (ignoreEventList.contains(itemName + command.toString())) {
			// if we have received this event from knx, don't send it back to
			// the knx bus
			ignoreEventList.remove(itemName + command.toString());
		} else {
			Datapoint datapoint = getDatapoint(itemName, command.getClass());
			if (datapoint != null) {
				ProcessCommunicator pc = KNXConnection.getCommunicator();
				if (pc != null) {
					try {
						pc.write(datapoint, toDPTValue(command));
					} catch (KNXException e) {
						logger.error("Command could not be sent to the KNX bus!", e);
					}
				}
			}
		}
	}

	@Override
	public void receiveUpdate(String itemName, State newState) {
		if (ignoreEventList.contains(itemName + newState.toString())) {
			// if we have received this event from knx, don't send it back to
			// the knx bus
			ignoreEventList.remove(itemName + newState.toString());
		} else {
			Datapoint datapoint = getDatapoint(itemName, newState.getClass());
			if (datapoint != null) {
				ProcessCommunicator pc = KNXConnection.getCommunicator();
				if (pc != null) {
					try {
						pc.write(datapoint, toDPTValue(newState));
					} catch (KNXException e) {
						logger.error("Update could not be sent to the KNX bus!", e);
						KNXConnection.connect();
					}
				}
			}
		}
	}

	public void groupWrite(ProcessEvent e) {
		try {
			GroupAddress destination = e.getDestination();
			byte[] asdu = e.getASDU();
			if(asdu.length==0) {
				return;
			}
			for (String itemName : getItemNames(destination)) {
				Datapoint datapoint = getDatapoint(itemName, destination);
				if(datapoint!=null) {
					Type type = getType(datapoint, asdu);					
					if(type!=null) {
						// we need to make sure that we won't send out this event to
						// the knx bus again, when receiving it on the openHAB bus
						ignoreEventList.add(itemName + type.toString());
			
						if (datapoint instanceof CommandDP) {
							eventPublisher.postCommand(itemName, (Command) type);
						} else if (datapoint instanceof StateDP) {
							eventPublisher.postUpdate(itemName, (State) type);
						}
					}
				}
			}
		} catch(RuntimeException re) {
			logger.error("Error while receiving event from KNX bus: " + re.toString());
		}
	}

	public void detached(DetachEvent e) {
		logger.error("Received detachEvent.");
	}

	@Override
	public void bindingChanged(KNXBindingProvider provider, String itemName) {
		for (Datapoint datapoint : provider.getReadableDatapoints()) {
			if(datapoint.getName().equals(itemName)) {
				datapointsToInitialize.add(datapoint);
			}
		}
	}

	@Override
	public void allBindingsChanged(KNXBindingProvider provider) {
		for (Datapoint datapoint : provider.getReadableDatapoints()) {
			datapointsToInitialize.add(datapoint);
		}
	}

	/**
	 * Returns all listening item names. This method iterates over all registered KNX binding providers and aggregates
	 * the result.
	 * 
	 * @param groupAddress
	 *            the group address that the items are listening to
	 * @return an array of all listening items
	 */
	private String[] getItemNames(GroupAddress groupAddress) {
		List<String> itemNames = new ArrayList<String>();
		for (KNXBindingProvider provider : providers) {
			for (String itemName : provider.getListeningItemNames(groupAddress)) {
				itemNames.add(itemName);
			}
		}
		return itemNames.toArray(new String[itemNames.size()]);
	}

	/**
	 * Returns the datapoint for a given item and group address. This method iterates over all registered KNX binding
	 * providers to find the result.
	 * 
	 * @param itemName
	 *            the item name for the datapoint
	 * @param groupAddress
	 *            the group address associated to the datapoint
	 * @return the datapoint which corresponds to the given item and group address
	 */
	private Datapoint getDatapoint(String itemName, GroupAddress groupAddress) {
		for (KNXBindingProvider provider : providers) {
			Datapoint datapoint = provider.getDatapoint(itemName, groupAddress);
			if (datapoint != null)
				return datapoint;
		}
		return null;
	}

	/**
	 * Transforms the raw KNX bus data of a given datapoint into an openHAB type (command or state)
	 * 
	 * @param datapoint
	 *            the datapoint to which the data belongs
	 * @param asdu
	 *            the byte array of the raw data from the KNX bus
	 * @return the openHAB command or state that corresponds to the data
	 */
	private Type getType(Datapoint datapoint, byte[] asdu) {
		for (KNXTypeMapper typeMapper : typeMappers) {
			Type type = typeMapper.toType(datapoint, asdu);
			if (type != null)
				return type;
		}
		return null;
	}

	/**
	 * Returns the datapoint for a given item and type class. This method iterates over all registered KNX binding
	 * providers to find the result.
	 * 
	 * @param itemName
	 *            the item name for the datapoint
	 * @param typeClass
	 *            the type class associated to the datapoint
	 * @return the datapoint which corresponds to the given item and type class
	 */
	private Datapoint getDatapoint(String itemName, Class<? extends Type> typeClass) {
		for (KNXBindingProvider provider : providers) {
			Datapoint datapoint = provider.getDatapoint(itemName, typeClass);
			if (datapoint != null)
				return datapoint;
		}
		return null;
	}

	/**
	 * Transforms an openHAB type (command or state) into a datapoint type value for the KNX bus.
	 * 
	 * @param type
	 *            the openHAB command or state to transform
	 * @return the corresponding KNX datapoint type value as a string
	 */
	private String toDPTValue(Type type) {
		for (KNXTypeMapper typeMapper : typeMappers) {
			String value = typeMapper.toDPValue(type);
			if (value != null)
				return value;
		}
		return null;
	}

	/**
	 * The DatapointInitializer runs as a separate thread. Whenever new KNX bindings are added, it takes care that read
	 * requests are sent to all new datapoints, which support this request. By this, the initial status can be
	 * determined and one does not have to stay in an "undefined" state until the first telegram is sent on the NX bus
	 * for this datapoint. As there might be hundreds of datapoints added at the same time and we do not want to flood
	 * the KNX bus with read requests, we wait a configurable period of milliseconds between two requests. As a result,
	 * this might be quite long running and thus is executed in its own thread.
	 * 
	 * @author Kai Kreuzer
	 * @since 0.3.0
	 * 
	 */
	private class DatapointInitializer extends Thread {

		private boolean interrupted = false;
		
		public DatapointInitializer() {
			super("KNX datapoint initializer");
		}

		public void setInterrupted(boolean interrupted) {
			this.interrupted = interrupted;
		}

		@Override
		public void run() {
			// as long as no interrupt is requested, continue running
			while (!interrupted) {
				if(datapointsToInitialize.size() > 0) {
					// we first clone the list, so that it stays unmodified
					Collection<Datapoint> clonedList = new HashSet<Datapoint>(datapointsToInitialize);
					initializeDatapoints(clonedList);
				}
				// just wait before looping again
				try {
					sleep(1000L);
				} catch (InterruptedException e) {
					interrupted = true;
				}
			}
		}

		private void initializeDatapoints(Collection<Datapoint> readableDatapoints) {
			for (Datapoint datapoint : readableDatapoints) {
				if (datapoint instanceof StateDP) {
					try {
						ProcessCommunicator pc = KNXConnection.getCommunicator();
						if (pc != null) {
							logger.debug("Sending read request to KNX for item {}", datapoint.getName());
							String value = pc.read(datapoint);
							DPTXlator translator = TranslatorTypes.createTranslator(datapoint.getMainNumber(),
									datapoint.getDPT());
							translator.setValue(value);
							byte[] data = translator.getData();
							Type state = getType(datapoint, data);
							eventPublisher.postUpdate(datapoint.getName(), (org.openhab.core.types.State) state);
						}
					} catch (KNXException e) {
						logger.warn("Cannot read value for item '{}' from KNX bus!", datapoint.getName(), e);
						logger.error(e.getMessage());
					} catch (KNXIllegalArgumentException e) {
						logger.warn("Error sending KNX read request for '{}'!", datapoint.getName(), e);
					}
				}
				datapointsToInitialize.remove(datapoint);
				long pause = KNXConnection.getReadingPause();
				if (pause > 0) {
					try {
						sleep(pause);
					} catch (InterruptedException e) {
						logger.debug("KNX reading pause has been interrupted!", e);
					}
				}
			}
		}
	}

}