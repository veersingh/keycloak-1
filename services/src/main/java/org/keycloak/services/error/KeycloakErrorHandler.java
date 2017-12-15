package org.keycloak.services.error;

import org.jboss.logging.Logger;
import org.jboss.resteasy.spi.Failure;
import org.jboss.resteasy.spi.HttpResponse;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.keycloak.Config;
import org.keycloak.forms.login.freemarker.model.UrlBean;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.RealmModel;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.util.LocaleHelper;
import org.keycloak.theme.FreeMarkerUtil;
import org.keycloak.theme.Theme;
import org.keycloak.theme.ThemeProvider;
import org.keycloak.theme.beans.LocaleBean;
import org.keycloak.theme.beans.MessageBean;
import org.keycloak.theme.beans.MessageFormatterMethod;
import org.keycloak.theme.beans.MessageType;
import org.keycloak.utils.MediaType;
import org.keycloak.utils.MediaTypeMatcher;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Provider
public class KeycloakErrorHandler implements ExceptionMapper<Throwable> {

    private static final Logger logger = Logger.getLogger(KeycloakErrorHandler.class);

    private static final Pattern realmNamePattern = Pattern.compile(".*/realms/([^/]+).*");

    @Context
    private UriInfo uriInfo;

    @Context
    private KeycloakSession session;

    @Context
    private HttpHeaders headers;

    @Context
    private HttpResponse response;

    @Override
    public Response toResponse(Throwable throwable) {
        KeycloakTransaction tx = ResteasyProviderFactory.getContextData(KeycloakTransaction.class);
        tx.setRollbackOnly();

        int statusCode = getStatusCode(throwable);

        if (statusCode >= 500 && statusCode <= 599) {
            logger.error("Uncaught server error", throwable);
        }

        if (!MediaTypeMatcher.isHtmlRequest(headers)) {
            return Response.status(statusCode).build();
        }

        try {
            RealmModel realm = resolveRealm();

            ThemeProvider themeProvider = session.getProvider(ThemeProvider.class, "extending");
            Theme theme = themeProvider.getTheme(realm.getLoginTheme(), Theme.Type.LOGIN);

            Locale locale = LocaleHelper.getLocale(session, realm, null);

            FreeMarkerUtil freeMarker = new FreeMarkerUtil();
            Map<String, Object> attributes = initAttributes(realm, theme, locale, statusCode);

            String templateName = "error.ftl";

            String content = freeMarker.processTemplate(attributes, templateName, theme);
            return Response.status(statusCode).type(MediaType.TEXT_HTML_UTF_8_TYPE).entity(content).build();
        } catch (Throwable t) {
            logger.error("Failed to create error page", t);
            return Response.serverError().build();
        }
    }

    private int getStatusCode(Throwable throwable) {
        int status = Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
        if (throwable instanceof WebApplicationException) {
            WebApplicationException ex = (WebApplicationException) throwable;
            status = ex.getResponse().getStatus();
        }
        if (throwable instanceof Failure) {
            Failure f = (Failure) throwable;
            status = f.getErrorCode();
        }
        return status;
    }

    private RealmModel resolveRealm() {
        String path = uriInfo.getPath();
        Matcher m = realmNamePattern.matcher(path);
        String realmName;
        if(m.matches()) {
            realmName = m.group(1);
        } else {
            realmName = Config.getAdminRealm();
        }

        RealmManager realmManager = new RealmManager(session);
        RealmModel realm = realmManager.getRealmByName(realmName);
        if (realm == null) {
            realm = realmManager.getRealmByName(Config.getAdminRealm());
        }

        return realm;
    }

    private Map<String, Object> initAttributes(RealmModel realm, Theme theme, Locale locale, int statusCode) throws IOException {
        Map<String, Object> attributes = new HashMap<>();
        Properties messagesBundle = theme.getMessages(locale);

        attributes.put("statusCode", statusCode);

        attributes.put("realm", realm);
        attributes.put("url", new UrlBean(realm, theme, uriInfo.getBaseUri(), null));
        attributes.put("locale", new LocaleBean(realm, locale, uriInfo.getBaseUriBuilder(), messagesBundle));


        String errorKey = statusCode == 404 ? Messages.PAGE_NOT_FOUND : Messages.INTERNAL_SERVER_ERROR;
        String errorMessage = messagesBundle.getProperty(errorKey);

        attributes.put("message", new MessageBean(errorMessage, MessageType.ERROR));

        try {
            attributes.put("msg", new MessageFormatterMethod(locale, theme.getMessages(locale)));
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            attributes.put("properties", theme.getProperties());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return attributes;
    }

}