package uk.ac.open.kmi.forge.ptAnywhere.api.websocket;

import com.cisco.pt.ipc.enums.DeviceType;
import com.cisco.pt.ipc.events.TerminalLineEvent;
import com.cisco.pt.ipc.events.TerminalLineEventListener;
import com.cisco.pt.ipc.events.TerminalLineEventRegistry;
import com.cisco.pt.ipc.sim.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import uk.ac.open.kmi.forge.ptAnywhere.analytics.InteractionRecord;
import uk.ac.open.kmi.forge.ptAnywhere.analytics.InteractionRecordFactory;
import uk.ac.open.kmi.forge.ptAnywhere.api.http.Utils;
import uk.ac.open.kmi.forge.ptAnywhere.gateway.PTConnection;
import uk.ac.open.kmi.forge.ptAnywhere.session.PTInstanceDetails;
import uk.ac.open.kmi.forge.ptAnywhere.session.SessionsManager;

import java.io.IOException;
import java.lang.ref.WeakReference;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;


@ServerEndpoint("/endpoint/sessions/{session}/devices/{device}/console")
public class ConsoleEndpoint implements TerminalLineEventListener {

    private final static Log LOGGER = LogFactory.getLog(ConsoleEndpoint.class);

    // "lrsFactory" is a weak reference because it is handled by the APIApplication class.
    // TODO better way to access it directly in the APIApplication (which is the manager).
    private static WeakReference<InteractionRecordFactory> lrsFactory;

    PTConnection common;
    TerminalLine cmd;
    Session session;

    public ConsoleEndpoint() {}

    public static void setFactory(InteractionRecordFactory irf) {
        ConsoleEndpoint.lrsFactory = new WeakReference<InteractionRecordFactory>(irf);
    }

    private InteractionRecord createInteractionRecordSession() {
        final InteractionRecordFactory irf = ConsoleEndpoint.lrsFactory.get();
        if (irf==null) return null;
        return ConsoleEndpoint.lrsFactory.get().create();
    }

    private String getSessionId(Session session) {
        return session.getPathParameters().get("session");
    }

    private String getDeviceId(Session session) {
        return session.getPathParameters().get("device");
    }

    private String getDeviceURI(Session session) {
        return "http://forge.kmi.open.ac.uk/pt/" + getDeviceId(session);
    }

    @OnOpen
    public void myOnOpen(final Session session) {
        this.session = session;  // Important, the first thing

        final PTInstanceDetails details = SessionsManager.create().getInstance(getSessionId(session));
        if (details==null) return; // Is it better to throw an exception?

        this.common = PTConnection.createPacketTracerGateway(details.getHost(), details.getPort());
        this.common.open();
        final String deviceId = getDeviceId(session);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Opening communication channel for device " + deviceId + "'s command line.");
        }
        final Device dev = this.common.getDataAccessObject().getSimDeviceById(Utils.toCiscoUUID(deviceId));
        if (dev==null) {
            if(LOGGER.isErrorEnabled()) {
                LOGGER.error("Device with id " + deviceId + " not found." );
            }
        } else {
            if (DeviceType.PC.equals(dev.getType()) || DeviceType.SWITCH.equals(dev.getType()) ||
                DeviceType.ROUTER.equals(dev.getType())) {
                if (DeviceType.PC.equals(dev.getType())) {
                    this.cmd = ((Pc) dev).getCommandLine();
                } else {
                    this.cmd = ((CiscoDevice) dev).getConsoleLine();
                }
                try {
                    final TerminalLineEventRegistry registry = this.common.getTerminalLineEventRegistry();
                    registry.addListener(this, this.cmd);
                    registerActivityStart(session);
                    if (this.cmd.getPrompt().equals("")) {
                        // Switches and router need a "RETURN" to get started.
                        // Here, we free the client from doing this task.
                        enterCommand(session, "", false);
                    } else
                        this.session.getBasicRemote().sendText(this.cmd.getPrompt());
                } catch (IOException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            } else {
                if(LOGGER.isErrorEnabled()) {
                    LOGGER.error("Console could not opened for device type " + dev.getType().name() + ".");
                }
            }
        }
    }

    @OnClose
    public void myOnClose(final CloseReason reason) {
        try {
            //System.out.println("Closing a WebSocket due to " + reason.getReasonPhrase());
            this.common.getTerminalLineEventRegistry().removeListener(this, this.cmd);
            registerActivityEnd(this.session);
        } catch(IOException e) {
            LOGGER.error(e.getMessage(), e);
        } finally {
            this.common.close();
        }
    }

    private void enterCommand(Session session, String msg, boolean last) {
        if (session.isOpen()) {
            final String sessionId = session.getPathParameters().get("session");
            if (SessionsManager.create().doesExist(sessionId)) {
                this.cmd.enterCommand(msg);
            } else {
                // The current session no longer has access to the PT instance it was using...
                final TerminalLineEventRegistry registry = this.common.getTerminalLineEventRegistry();
                try {
                    registry.removeListener(this);
                    session.getBasicRemote().sendText("\n\n\nThis command line does no longer accept commands.");
                    session.getBasicRemote().sendText("\nYour session might have expired.");
                    session.close();
                } catch(IOException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
        }
    }

    private void registerActivityStart(Session session) {
        final InteractionRecord ir = createInteractionRecordSession();
        if (ir==null) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("No interaction record.");
            }
        } else {
            ir.commandLineStarted(getSessionId(session), getDeviceURI(session));
        }
    }

    private void registerInteraction(Session session, String msg) {
        final InteractionRecord ir = createInteractionRecordSession();
        if (ir==null) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("No interaction record.");
            }
        } else {
            ir.commandLineUsed(getSessionId(session), getDeviceURI(session), msg);
        }
    }

    private void registerActivityEnd(Session session) {
        final InteractionRecord ir = createInteractionRecordSession();
        if (ir==null) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("No interaction record.");
            }
        } else {
            ir.commandLineEnded(getSessionId(session), getDeviceURI(session));
        }
    }

    @OnMessage
    public void typeCommand(Session session, String msg, boolean last) {
        // register it in Tin Can API
        enterCommand(session, msg, last);
        registerInteraction(session, msg);
    }

    public void handleEvent(TerminalLineEvent event) {
        if (event.eventName.equals("outputWritten")) {
            try {
                final String msg = ((TerminalLineEvent.OutputWritten) event).newOutput;
                this.session.getBasicRemote().sendText(msg);
            } catch(IOException e) {
                LOGGER.error(e.getMessage(), e);
            }
        }
    }
}