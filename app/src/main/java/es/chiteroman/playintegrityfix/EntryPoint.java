package es.chiteroman.playintegrityfix;

import android.os.Build;
import android.util.JsonReader;
import android.util.Log;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.Security;
import java.util.HashMap;
import java.util.Map;

public final class EntryPoint {
    private static Integer verboseLogs = 0;

    private static final Map<String, String> map = new HashMap<>();

    public static void init() {
        spoofProvider();
        spoofDevice();
        if (verboseLogs > 99) logFields();
    }

    public static void readJson(String data) {
        try (JsonReader reader = new JsonReader(new StringReader(data))) {
            reader.beginObject();
            while (reader.hasNext()) {
                map.put(reader.nextName(), reader.nextString());
            }
            reader.endObject();
        } catch (IOException|IllegalStateException e) {
            LOG("Couldn't read JSON from Zygisk: " + e);
            map.clear();
            return;
        }
    }

    private static void spoofProvider() {
        final String KEYSTORE = "AndroidKeyStore";

        try {
            Provider provider = Security.getProvider(KEYSTORE);
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);

            Field f = keyStore.getClass().getDeclaredField("keyStoreSpi");
            f.setAccessible(true);
            CustomKeyStoreSpi.keyStoreSpi = (KeyStoreSpi) f.get(keyStore);
            f.setAccessible(false);

            CustomProvider customProvider = new CustomProvider(provider);
            Security.removeProvider(KEYSTORE);
            Security.insertProviderAt(customProvider, 1);

            LOG("Spoof KeyStoreSpi and Provider done!");

        } catch (KeyStoreException e) {
            LOG("Couldn't find KeyStore: " + e);
        } catch (NoSuchFieldException e) {
            LOG("Couldn't find field: " + e);
        } catch (IllegalAccessException e) {
            LOG("Couldn't change access of field: " + e);
        }
    }

    static void spoofDevice() {
        for (String key : map.keySet()) {
            // Verbose logging if VERBOSE_LOGS with level number is last entry
            if (key.equals("VERBOSE_LOGS")) {
                verboseLogs = Integer.parseInt(map.get("VERBOSE_LOGS"));
            } else {
                setField(key, map.get(key));
            }
        }
    }

    private static boolean classContainsField(Class className, String fieldName) {
        for (Field field : className.getDeclaredFields()) {
            if (field.getName().equals(fieldName)) return true;
        }
        return false;
    }

    private static void setField(String name, String value) {
        if (value.isEmpty()) {
            LOG(String.format("%s is empty, skipping...", name));
            return;
        }

        Field field = null;
        String oldValue = null;
        Object newValue = null;

        try {
            if (classContainsField(Build.class, name)) {
                field = Build.class.getDeclaredField(name);
            } else if (classContainsField(Build.VERSION.class, name)) {
                field = Build.VERSION.class.getDeclaredField(name);
            } else {
                if (verboseLogs > 1) LOG(String.format("Couldn't determine '%s' class name", name));
                return;
            }
        } catch (NoSuchFieldException e) {
            LOG(String.format("Couldn't find '%s' field name: " + e, name));
            return;
        }
        field.setAccessible(true);
        try {
            oldValue = String.valueOf(field.get(null));
        } catch (IllegalAccessException e) {
            LOG(String.format("Couldn't access '%s' field value: " + e, name));
            return;
        }
        if (value.equals(oldValue)) {
            if (verboseLogs > 2) LOG(String.format("[%s]: %s (unchanged)", name, value));
            return;
        }
        Class<?> fieldType = field.getType();
        if (fieldType == String.class) {
            newValue = value;
        } else if (fieldType == int.class) {
            newValue = Integer.parseInt(value);
        } else if (fieldType == long.class) {
            newValue = Long.parseLong(value);
        } else if (fieldType == boolean.class) {
            newValue = Boolean.parseBoolean(value);
        } else {
            LOG(String.format("Couldn't convert '%s' to '%s' type", value, fieldType));
            return;
        }
        try {
            field.set(null, newValue);
        } catch (IllegalAccessException e) {
            LOG(String.format("Couldn't modify '%s' field value: " + e, name));
            return;
        }
        field.setAccessible(false);
        LOG(String.format("[%s]: %s -> %s", name, oldValue, value));
    }

    private static String logParseField(Field field) {
        Object value = null;
        String type = field.getType().getName();
        String name = field.getName();
        try {
            value = field.get(null);
        } catch (IllegalAccessException|NullPointerException e) {
            return String.format("Couldn't access '%s' field value: " + e, name);
        }
        return String.format("<%s> %s: %s", type, name, String.valueOf(value));
    }

    private static void logFields() {
        for (Field field : Build.class.getDeclaredFields()) {
            LOG("Build " + logParseField(field));
        }
        for (Field field : Build.VERSION.class.getDeclaredFields()) {
            LOG("Build.VERSION " + logParseField(field));
        }
    }

    static void LOG(String msg) {
        Log.d("PIF/Java", msg);
    }
}
