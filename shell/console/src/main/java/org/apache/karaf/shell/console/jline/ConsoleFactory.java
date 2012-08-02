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
import java.util.Hashtable;
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
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.blueprint.container.BlueprintEvent;
import org.osgi.service.blueprint.container.BlueprintListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConsoleFactory implements BundleListener, ServiceListener, BlueprintListener {

	private transient Logger log = LoggerFactory.getLogger(ConsoleFactory.class);
    private BundleContext bundleContext;
    private CommandProcessor commandProcessor;
    private TerminalFactory terminalFactory;
    private Console console;
    private boolean start;
    private RuntimeStatus runtimeStatus = new RuntimeStatus();
    private AtomicBoolean executorInvoked = new AtomicBoolean(false);
    private ServiceRegistration blueprintListenerReg;
    
	class RuntimeStatus {
		public int events;
		public int activeServices;
		public int unstableBundles;
		public int unstableBlueprints;
    }

    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    public synchronized void registerCommandProcessor(CommandProcessor commandProcessor) throws Exception {
        this.commandProcessor = commandProcessor;
        start();
        if (Boolean.getBoolean("karaf.executeCommands"))
        	prepareExecuteCommands();
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

    public static Object invokePrivateMethod(Object o, String methodName, Object[] params) throws Exception {
        final Method methods[] = o.getClass().getDeclaredMethods();
        for (int i = 0; i < methods.length; ++i) {
            if (methodName.equals(methods[i].getName())) {
                methods[i].setAccessible(true);
                return methods[i].invoke(o, params);
            }
        }
        return null;
    }
    
    private static <T> T unwrapBIS(T stream) {
        try {
             return (T) invokePrivateMethod(stream, "getInIfOpen", null);
        } catch (Throwable t) {
             return stream;
        }
    }

    protected void doStart(String user) throws Exception {
        final Terminal terminal = terminalFactory.getTerminal();
        // unwrap stream so it can be recognized by the terminal and wrapped to get 
        // special keys in windows
        InputStream unwrappedIn = unwrapBIS(unwrap(System.in));
        InputStream in = terminal.wrapInIfNeeded(unwrappedIn);
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
        // The bundle is stopped
        // so close the console and remove the callback so that the
        // osgi framework isn't stopped
        if (console != null) {
            console.close(false);
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

    /**
     * Sets up listeners to monitor the runtime state, waits until the runtime
     * is "stable" (all services and bundles are active, etc.) then invokes the commands executor.
     */
    private void prepareExecuteCommands() {
    	Bundle[] bundles = bundleContext.getBundles();
    	synchronized (runtimeStatus) {
    		// Temporarily add self as listeners
        	bundleContext.addBundleListener(this);
        	bundleContext.addServiceListener(this);
        	// Get current number of unstable bundles
	    	for (Bundle bundle : bundles) {
	    		if (bundle.getState() == Bundle.STARTING || bundle.getState() == Bundle.STOPPING) {
	    			runtimeStatus.unstableBundles++;
	    		}
	    	}
	    	// Get current number of active services
			try {
				ServiceReference[] services = bundleContext.getAllServiceReferences(null, null);
				runtimeStatus.activeServices = services.length;
			} catch (InvalidSyntaxException e) {
			}
    	}
    	// Register Blueprint listener, must be last to avoid deadlock on runtimeStatus
    	blueprintListenerReg = bundleContext.registerService(BlueprintListener.class.getName(), this, new Hashtable<String, String>());
	}

	public void bundleChanged(BundleEvent event) {
		synchronized (runtimeStatus) {
			runtimeStatus.events++;
			if (event.getType() == BundleEvent.STARTED || event.getType() == BundleEvent.STOPPED) {
				runtimeStatus.unstableBundles--;
			} else if (event.getType() == BundleEvent.STARTING || event.getType() == BundleEvent.STOPPING) {
				runtimeStatus.unstableBundles++;
			}
			runtimeStatus.notifyAll();
		}
		invokeExecutorIfStable();
	}

	public void serviceChanged(ServiceEvent event) {
		synchronized (runtimeStatus) {
			runtimeStatus.events++;
			if (event.getType() == ServiceEvent.REGISTERED) {
				runtimeStatus.activeServices++;
//				System.out.print(sth.activeServices + " ");
			} else if (event.getType() == ServiceEvent.UNREGISTERING) {
				runtimeStatus.activeServices--;
//				System.out.print(sth.activeServices + " ");
			}
			runtimeStatus.notifyAll();
		}
		invokeExecutorIfStable();
	}

	public void blueprintEvent(BlueprintEvent event) {
		synchronized (runtimeStatus) {
			runtimeStatus.events++;
			if (event.getType() == BlueprintEvent.CREATING || event.getType() == BlueprintEvent.WAITING || event.getType() == BlueprintEvent.DESTROYING) {
				runtimeStatus.unstableBlueprints++;
			} else if (event.getType() == BlueprintEvent.CREATED || event.getType() == BlueprintEvent.FAILURE || event.getType() == BlueprintEvent.DESTROYED) {
				if (runtimeStatus.unstableBlueprints > 0)
					runtimeStatus.unstableBlueprints--;
			}
			runtimeStatus.notifyAll();
		}
		invokeExecutorIfStable();
	}
	
    /**
     * Creates a non-terminal session, executes commands in <tt>karaf.commands</tt>
     * then immediately shuts down the OSGi runtime.
     */
    private void executeCommands() {
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
    
    /**
     * Waits for OSGi runtime to stabilize for the last time, then executes commands in "karaf.commands"
     * by calling {@link ConsoleFactory#executeCommands()}.
     */
    private void invokeExecutor() {
    	log.info("Waiting for OSGi runtime to stabilize");
    	new Thread("Wait for stable") {
    		@Override
    		public void run() {
				while (true) {
					try {
    					int lastEvents; 
		    			synchronized (runtimeStatus) {
	    					lastEvents = runtimeStatus.events; 
							runtimeStatus.wait(100);
//							System.out.print("*" + sth.activeServices + " ");
							if (lastEvents == runtimeStatus.events && runtimeStatus.unstableBundles <= 0 && runtimeStatus.unstableBlueprints <= 0) {
								log.info("Time to launch command! {} services found", runtimeStatus.activeServices);
//								System.out.println(String.format("Time to launch command! %d services found", sth.activeServices));
								
								// Unregister listeners
								if (blueprintListenerReg != null) {
									blueprintListenerReg.unregister();
									blueprintListenerReg = null;
								}
								bundleContext.removeServiceListener(ConsoleFactory.this);
								bundleContext.removeBundleListener(ConsoleFactory.this);
								
								executeCommands();
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

    /**
     * Checks if the OSGi runtime is "stable" and calls {@link ConsoleFactory#invokeExecutor()}.
     */
    private void invokeExecutorIfStable() {
		boolean prevState = executorInvoked.getAndSet(executorInvoked.get() ||
				(runtimeStatus.unstableBundles <= 0 && runtimeStatus.unstableBlueprints <= 0));
		if (prevState == false && executorInvoked.get()) {
			invokeExecutor();
		}
    }
    
}
