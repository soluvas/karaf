/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.karaf.shell.console.jline;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.security.PrivilegedExceptionAction;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.security.auth.Subject;

import jline.Terminal;

import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;
import org.apache.felix.service.command.Converter;
import org.apache.felix.service.command.Function;
import org.apache.karaf.jaas.modules.UserPrincipal;
import org.fusesource.jansi.AnsiConsole;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.BundleListener;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.blueprint.container.BlueprintEvent;
import org.osgi.service.blueprint.container.BlueprintListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConsoleFactory implements BlueprintListener {

	private transient Logger log = LoggerFactory.getLogger(ConsoleFactory.class);
    private BundleContext bundleContext;
    private CommandProcessor commandProcessor;
    private TerminalFactory terminalFactory;
    private Console console;
    private boolean start;

    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    public synchronized void registerCommandProcessor(CommandProcessor commandProcessor) throws Exception {
        this.commandProcessor = commandProcessor;
        start();
        if (Boolean.getBoolean("karaf.executeCommands"))
        	monitorStartLevel(bundleContext);
    }

    public synchronized void unregisterCommandProcessor(CommandProcessor commandProcessor) throws Exception {
        this.commandProcessor = null;
        stop();
    }

    public void setTerminalFactory(TerminalFactory terminalFactory) {
        this.terminalFactory = terminalFactory;
    }

    public void setStart(boolean start) {
        this.start = start;
    }

    protected void start() throws Exception {
        if (start) {
            Subject subject = new Subject();
            final String user = "karaf";
            subject.getPrincipals().add(new UserPrincipal(user));
            Subject.doAs(subject, new PrivilegedExceptionAction<Object>() {
                public Object run() throws Exception {
                    doStart(user);
                    return null;
                }
            });
        }
    }

    protected void doStart(String user) throws Exception {
        InputStream in = unwrap(System.in);
        PrintStream out = unwrap(System.out);
        PrintStream err = unwrap(System.err);
        Runnable callback = new Runnable() {
            public void run() {
                try {
                    bundleContext.getBundle(0).stop();
                } catch (Exception e) {
                    // Ignore
                }
            }
        };
        final Terminal terminal = terminalFactory.getTerminal();
        this.console = new Console(commandProcessor,
                                   in,
                                   wrap(out),
                                   wrap(err),
                                   terminal,
                                   callback);
        CommandSession session = console.getSession();
        session.put("USER", user);
        session.put("APPLICATION", System.getProperty("karaf.name", "root"));
        session.put("#LINES", new Function() {
            public Object execute(CommandSession session, List<Object> arguments) throws Exception {
                return Integer.toString(terminal.getHeight());
            }
        });
        session.put("#COLUMNS", new Function() {
            public Object execute(CommandSession session, List<Object> arguments) throws Exception {
                return Integer.toString(terminal.getWidth());
            }
        });
        session.put(".jline.terminal", terminal);
        new Thread(console, "Karaf Shell Console Thread").start();
    }

    protected void stop() throws Exception {
        if (console != null) {
            console.close();
        }
    }

    private static PrintStream wrap(PrintStream stream) {
        OutputStream o = AnsiConsole.wrapOutputStream(stream);
        if (o instanceof PrintStream) {
            return ((PrintStream) o);
        } else {
            return new PrintStream(o);
        }
    }

    private static <T> T unwrap(T stream) {
        try {
            Method mth = stream.getClass().getMethod("getRoot");
            return (T) mth.invoke(stream);
        } catch (Throwable t) {
            return stream;
        }
    }
    
    private void executeCommands(final BundleContext bundleCtx) {
		String commands = System.getProperty("karaf.commands", "");
		log.info("Executing: {}", commands);
//		System.out.println("Executing: "+ commands);

        InputStream in = unwrap(System.in);
        PrintStream out = unwrap(System.out);
        PrintStream err = unwrap(System.err);
		CommandSession session = commandProcessor.createSession(in, out, err);
		try {
	        session.put("SCOPE", "shell:osgi:*");
	        session.put("APPLICATION", System.getProperty("karaf.name", "root"));
	        try {
				Object result = session.execute(commands);
//				System.out.println("Result is " + result);
				if (result != null) {
					session.getConsole().println(
					  session.format(result, Converter.INSPECT));
				}
			} catch (Exception e) {
				err.println("Error executing command: " + e);
				e.printStackTrace(err);
			}
		} finally {
			session.close();
		}

		// Shutdown OSGi Runtime
        Bundle bundle = bundleContext.getBundle(0);
        try {
			bundle.stop();
		} catch (BundleException e) {
			log.error("Error when shutting down", e);
		}
    }
    
    private void waitForStable(final BundleContext bundleCtx) {
    	log.info("Waiting for OSGi runtime to stabilize");
    	new Thread("Wait for stable") {
    		@Override
    		public void run() {
				while (true) {
					try {
    					int lastEvents; 
		    			synchronized (sth) {
	    					lastEvents = sth.events; 
							sth.wait(100);
//							System.out.print("*" + sth.activeServices + " ");
							if (lastEvents == sth.events && sth.unstableBundles <= 0 && sth.unstableBlueprints <= 0) {
								log.info("Time to launch command! {} services found", sth.activeServices);
//								System.out.println(String.format("Time to launch command! %d services found", sth.activeServices));
								executeCommands(bundleCtx);
								break;
							}
		    			}
					} catch (InterruptedException e) {
						log.info("Interrupted", e);
						break;
					}
    			}
    		}
    	}.start();
	}

	class Something {
		public int events;
		public int activeServices;
		public int unstableBundles;
		public int unstableBlueprints;
    }
    private Something sth = new Something();
    private AtomicBoolean waitingForStable = new AtomicBoolean();
    
    private void monitorStartLevel(final BundleContext context) {
    	Bundle[] bundles = context.getBundles();
    	synchronized (sth) {
        	context.addBundleListener(new BundleListener() {
    			
    			public void bundleChanged(BundleEvent event) {
    				synchronized (sth) {
    					sth.events++;
    					if (event.getType() == BundleEvent.STARTED || event.getType() == BundleEvent.STOPPED) {
    						sth.unstableBundles--;
    					} else if (event.getType() == BundleEvent.STARTING || event.getType() == BundleEvent.STOPPING) {
    						sth.unstableBundles++;
    					}
    						
    					boolean prevState = waitingForStable.getAndSet(waitingForStable.get() ||
    							(sth.activeServices >= 1 && sth.unstableBundles <= 0 && sth.unstableBlueprints <= 0));
    					if (prevState == false && waitingForStable.get()) {
    						waitForStable(context);
    					}
    					sth.notifyAll();
    				}
    			}
    		});
        	
        	context.addServiceListener(new ServiceListener() {
    			public void serviceChanged(ServiceEvent event) {
    				synchronized (sth) {
    					sth.events++;
    					if (event.getType() == ServiceEvent.REGISTERED) {
    						sth.activeServices++;
//    						System.out.print(sth.activeServices + " ");
    					} else if (event.getType() == ServiceEvent.UNREGISTERING) {
    						sth.activeServices--;
//    						System.out.print(sth.activeServices + " ");
    					}
    					boolean prevState = waitingForStable.getAndSet(waitingForStable.get() ||
    							(sth.activeServices >= 1 && sth.unstableBundles <= 0 && sth.unstableBlueprints <= 0));
    					if (prevState == false && waitingForStable.get()) {
    						waitForStable(context);
    					}
    					sth.notifyAll();
    				}
    			}
    		});
    		
        	// Get current number of unstable bundles
	    	for (Bundle bundle : bundles) {
	    		if (bundle.getState() == Bundle.STARTING || bundle.getState() == Bundle.STOPPING) {
	    			sth.unstableBundles++;
	    		}
	    	}
	    	// Get current number of active services
			try {
				ServiceReference[] services = context.getAllServiceReferences(null, null);
				sth.activeServices = services.length;
			} catch (InvalidSyntaxException e) {
			}
    	}
	}

	public void blueprintEvent(BlueprintEvent event) {
		synchronized (sth) {
			sth.events++;
			if (event.getType() == BlueprintEvent.CREATING || event.getType() == BlueprintEvent.WAITING || event.getType() == BlueprintEvent.DESTROYING) {
				sth.unstableBlueprints++;
			} else if (event.getType() == BlueprintEvent.CREATED || event.getType() == BlueprintEvent.FAILURE || event.getType() == BlueprintEvent.DESTROYED) {
				if (sth.unstableBlueprints > 0)
					sth.unstableBlueprints--;
			}
			sth.notifyAll();
		}
	}
	
}
