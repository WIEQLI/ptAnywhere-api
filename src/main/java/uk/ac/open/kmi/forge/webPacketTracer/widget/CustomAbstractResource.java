package uk.ac.open.kmi.forge.webPacketTracer.widget;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.glassfish.jersey.server.mvc.Viewable;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public abstract class CustomAbstractResource {

    private static Log logger;
    private static Properties properties;

    static {
        logger = LogFactory.getLog(WidgetResource.class);
        properties = new Properties();  // It does not change once the app has been deployed.
    }


    @Context
    UriInfo uri;

    protected String getApplicationTitle() {
        try {
            properties.load(WidgetResource.class.getClassLoader().getResourceAsStream("environment.properties"));
        } catch (IOException e) {
            logger.error("Host and port of the PT instance could not be read from the properties file, using default values.");
        } finally {
            return properties.getProperty("title", "PacketTracer Widget");
        }
    }

    URI getAppRootURL() {
        return this.uri.getBaseUri().resolve("../");  // to remove "widget part"
    }

    public Viewable getPreFilled(String path) {
        return getPreFilled(path, new HashMap<String, Object>());
    }

    public Viewable getPreFilled(String path, Map<String, Object> map) {
        map.put("base", this.uri.getBaseUri().resolve("../").toString() + "static/");
        map.put("api", this.uri.getBaseUri().resolve("../").toString() + "api/");
        return (new Viewable(path, map));
    }
}