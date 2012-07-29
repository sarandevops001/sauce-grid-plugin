package com.saucelabs.grid;

import com.saucelabs.grid.services.SauceOnDemandRestAPIException;
import com.saucelabs.grid.services.SauceOnDemandService;
import com.saucelabs.grid.services.SauceOnDemandServiceImpl;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openqa.grid.common.JSONConfigurationUtils;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.internal.utils.CapabilityMatcher;
import org.openqa.grid.internal.utils.HtmlRenderer;
import org.openqa.grid.selenium.proxy.DefaultRemoteProxy;
import org.openqa.grid.web.servlet.handler.RequestType;
import org.openqa.grid.web.servlet.handler.SeleniumBasedRequest;
import org.openqa.grid.web.servlet.handler.WebDriverRequest;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.internal.HttpClientFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Fran�ois Reynaud - Initial version of plugin
 * @author Ross Rowe - Additional functionality
 */
public class SauceOnDemandRemoteProxy extends DefaultRemoteProxy {

    private static final Logger logger = Logger.getLogger(SauceOnDemandRemoteProxy.class.getName());
    /**
     * Send all Sauce OnDemand requests to ondemand.saucelabs.com/wd/hub.
     */
    public static String SAUCE_END_POINT = "/wd/hub";
    public static final String SAUCE_ONDEMAND_CONFIG_FILE = "sauce-ondemand.json";
    public static final String SAUCE_USER_NAME = "sauceUserName";
    public static final String SAUCE_ACCESS_KEY = "sauceAccessKey";
    private volatile boolean markUp = false;

    private String userName;
    private String accessKey;
    public static final String SAUCE_ENABLE = "sauceEnable";
    private boolean shouldProxySauceOnDemand = true;
    private boolean shouldHandleUnspecifiedCapabilities;
    public static final String SAUCE_HANDLE_UNSPECIFIED_CAPABILITIES = "sauceHandleUnspecifiedCapabilities";
    private CapabilityMatcher capabilityHelper;

    private SauceOnDemandService service = new SauceOnDemandServiceImpl();
    private int maxSauceSessions;
    private String[] webDriverCapabilities;
    public static final String SAUCE_WEB_DRIVER_CAPABILITIES = "sauceWebDriverCapabilities";
    public static final String SAUCE_RC_CAPABILITIES = "sauceSeleniumRCCapabilities";
    private String[] seleniumCapabilities;

    public boolean shouldProxySauceOnDemand() {
        return shouldProxySauceOnDemand;
    }

    public SauceOnDemandRemoteProxy(RegistrationRequest req, Registry registry) {
        super(updateDesiredCapabilities(req), registry);
        //TODO include proxy id in json file
        JSONObject sauceConfiguration = readConfigurationFromFile();
        try {
            this.userName = (String) req.getConfiguration().get(SAUCE_USER_NAME);
            this.accessKey = (String) req.getConfiguration().get(SAUCE_ACCESS_KEY);
            Object o = req.getConfiguration().get(SAUCE_HANDLE_UNSPECIFIED_CAPABILITIES);
            if (o != null) {
                this.shouldHandleUnspecifiedCapabilities = (Boolean) o;
            }
            if (userName != null && accessKey != null) {
                this.maxSauceSessions = service.getMaxiumumSessions(userName, accessKey);
            }
            Object b = req.getConfiguration().get(SAUCE_ENABLE);
            if (b != null) {
                shouldProxySauceOnDemand = Boolean.valueOf(b.toString());
            }

            if (sauceConfiguration != null) {
                if (sauceConfiguration.has(SAUCE_WEB_DRIVER_CAPABILITIES)) {
                    JSONArray keyArray = sauceConfiguration.getJSONArray(SAUCE_WEB_DRIVER_CAPABILITIES);
                    this.webDriverCapabilities = new String[keyArray.length()];
                    for (int i = 0; i < keyArray.length(); i++) {
                        webDriverCapabilities[i] = keyArray.getString(i);
                    }
                }
                if (sauceConfiguration.has(SAUCE_RC_CAPABILITIES)) {
                    JSONArray keyArray = sauceConfiguration.getJSONArray(SAUCE_RC_CAPABILITIES);
                    this.seleniumCapabilities = new String[keyArray.length()];
                    for (int i = 0; i < keyArray.length(); i++) {
                        seleniumCapabilities[i] = keyArray.getString(i);
                    }
                }
            }
        } catch (JSONException e) {
            logger.log(Level.SEVERE, "Error parsing JSON", e);
        } catch (SauceOnDemandRestAPIException e) {
            logger.log(Level.SEVERE, "Error invoking Sauce REST API", e);
        }

    }

    private static RegistrationRequest updateDesiredCapabilities(RegistrationRequest request) {
        //TODO ensure thread safety

        JSONObject sauceConfiguration = readConfigurationFromFile();
        try {
            if (sauceConfiguration != null) {

                //sauce end point should handle both SeleniumRC and WebDriver requests
                //capability.setCapability(RegistrationRequest.PATH, SAUCE_END_POINT);
                if (sauceConfiguration.has(SAUCE_USER_NAME)) {
                    request.getConfiguration().put(SAUCE_USER_NAME, sauceConfiguration.getString(SAUCE_USER_NAME));
                }
                if (sauceConfiguration.has(SAUCE_ACCESS_KEY)) {
                    request.getConfiguration().put(SAUCE_ACCESS_KEY, sauceConfiguration.getString(SAUCE_ACCESS_KEY));
                }
                if (sauceConfiguration.has(SAUCE_HANDLE_UNSPECIFIED_CAPABILITIES)) {
                    request.getConfiguration().put(SAUCE_HANDLE_UNSPECIFIED_CAPABILITIES, sauceConfiguration.getString(SAUCE_ACCESS_KEY));
                }
                if (sauceConfiguration.has(SAUCE_ENABLE)) {
                    request.getConfiguration().put(SAUCE_ENABLE, sauceConfiguration.getString(SAUCE_ENABLE));
                }

            }
        } catch (JSONException e) {
            logger.log(Level.SEVERE, "Error parsing JSON", e);
        }
        return request;
    }

    private static JSONObject readConfigurationFromFile() {

        File file = new File(SAUCE_ONDEMAND_CONFIG_FILE);
        if (file.exists()) {
            return JSONConfigurationUtils.loadJSON(file.getName());
        }
        return null;
    }


    @Override
    public boolean hasCapability(Map<String, Object> requestedCapability) {
        if (shouldHandleUnspecifiedCapabilities/* && browser combination is supported by sauce labs*/) {
            return true;
        }
        return super.hasCapability(requestedCapability);
    }

    /**
     * @param requestedCapability
     * @return
     */
    @Override
    public TestSession getNewSession(Map<String, Object> requestedCapability) {

        //if no proxy can handle requested capability, and shouldHandleUnspecifiedCapabilities is set to true
        //(and the browser capabillitiy is supported by Sauce), create new desired capability that runs
        //against sauce
        try {
            this.markUp = service.isSauceLabUp();
            if (!markUp) {
                //TODO log an error message?
            }
        } catch (SauceOnDemandRestAPIException e) {
            logger.log(Level.SEVERE, "Error invoking Sauce REST API", e);
        }
        if ((shouldProxySauceOnDemand && markUp) || !shouldProxySauceOnDemand) {
            return super.getNewSession(requestedCapability);
        } else {
            return null;
        }
    }

    @Override
    public CapabilityMatcher getCapabilityHelper() {
        if (capabilityHelper == null) {
            capabilityHelper = new SauceOnDemandCapabilityMatcher(this);
        }
        return this.capabilityHelper;

    }

    @Override
    public HtmlRenderer getHtmlRender() {
        return new SauceOnDemandRenderer(this);
    }

    @Override
    public int compareTo(RemoteProxy o) {
        if (!(o instanceof SauceOnDemandRemoteProxy)) {
            throw new RuntimeException("cannot mix saucelab and not saucelab ones");
        } else {
            SauceOnDemandRemoteProxy other = (SauceOnDemandRemoteProxy) o;

            if (this.shouldProxySauceOnDemand) {
                System.out.println("return -1, sslone");
                return 1;
            } else if (other.shouldProxySauceOnDemand) {
                return -1;
            } else {
                int i = super.compareTo(o);
                System.out.println("return normal " + i);
                return i;
            }
        }
    }

    public boolean contains(SauceOnDemandCapabilities capabilities) throws JSONException {
        for (TestSlot slot : getTestSlots()) {
            SauceOnDemandCapabilities slc = new SauceOnDemandCapabilities(slot.getCapabilities());
            if (slc.equals(capabilities)) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isMarkUp() {
        return markUp;
    }

    public synchronized void setMarkUp(boolean markUp) {
        this.markUp = markUp;
    }


    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public void writeConfigurationToFile() {
        JSONObject jsonObject = new JSONObject();

        try {
            jsonObject.put(SAUCE_USER_NAME, getUserName());
            jsonObject.put(SAUCE_ACCESS_KEY, getAccessKey());
            jsonObject.put(SAUCE_HANDLE_UNSPECIFIED_CAPABILITIES, shouldHandleUnspecifiedCapabilities());
            jsonObject.put(SAUCE_WEB_DRIVER_CAPABILITIES, getWebDriverCapabilities());
            //TODO handle selected browsers
            FileWriter file = new FileWriter(SAUCE_ONDEMAND_CONFIG_FILE);
            file.write(jsonObject.toString());
            file.flush();
            file.close();

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error parsing JSON", e);
        } catch (JSONException e) {
            logger.log(Level.SEVERE, "Error parsing JSON", e);
        }
    }

    public boolean shouldHandleUnspecifiedCapabilities() {
        return shouldHandleUnspecifiedCapabilities;
    }

    public void setShouldHandleUnspecifiedCapabilities(boolean shouldHandleUnspecifiedCapabilities) {
        this.shouldHandleUnspecifiedCapabilities = shouldHandleUnspecifiedCapabilities;
    }

    public URL getRemoteHost() {
        if (shouldHandleUnspecifiedCapabilities() //&& we don't have a node that can handle request
                ) {
            try {
                return new URL("http://ondemand.saucelabs.com:80");
            } catch (MalformedURLException e) {
                //shouldn't happen
                logger.log(Level.SEVERE, "Error constructing remote host url", e);
            }
        }
        return remoteHost;
    }

    public URL getNodeHost() {
        return remoteHost;
    }

    @Override
    public HttpClientFactory getHttpClientFactory() {
        return new SauceHttpClientFactory(this);
    }

    @Override
    public void beforeCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
        if (request instanceof WebDriverRequest && request.getMethod().equals("POST")) {
            WebDriverRequest seleniumRequest = (WebDriverRequest) request;
            if (seleniumRequest.getRequestType().equals(RequestType.START_SESSION)) {
                String body = seleniumRequest.getBody();
                //convert from String to JSON
                try {
                    JSONObject json = new JSONObject(body);
                    //add username/accessKey
                    JSONObject desiredCapabilities = json.getJSONObject("desiredCapabilities");
                    desiredCapabilities.put("username", this.userName);
                    desiredCapabilities.put("accessKey", this.accessKey);
                    //convert from JSON to String
                    seleniumRequest.setBody(json.toString());
                } catch (JSONException e) {
                    logger.log(Level.SEVERE, "Error parsing JSON", e);
                }
            }

        }
        super.beforeCommand(session, request, response);
    }

    @Override
    public int getMaxNumberOfConcurrentTestSessions() {
        if (shouldProxySauceOnDemand()) {
            return maxSauceSessions;
        }
        return super.getMaxNumberOfConcurrentTestSessions();
    }

    public void setWebDriverCapabilities(String[] webDriverCapabilities) {
        this.webDriverCapabilities = webDriverCapabilities;
    }

    public String[] getWebDriverCapabilities() {
        return webDriverCapabilities;
    }

    public void setSeleniumCapabilities(String[] seleniumCapabilities) {
        this.seleniumCapabilities = seleniumCapabilities;
    }
}