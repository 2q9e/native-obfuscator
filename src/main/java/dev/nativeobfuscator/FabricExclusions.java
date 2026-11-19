package dev.nativeobfuscator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class FabricExclusions {
    private FabricExclusions() {}

    static Map<String, String> from(Map<String, byte[]> jar) throws IOException {
        var classes = new HashMap<String, String>();
        if (!jar.containsKey("fabric.mod.json")) return classes;
        try {
            JsonObject mod = object(jar, "fabric.mod.json");
            if (mod.has("entrypoints")) {
                for (var category : mod.getAsJsonObject("entrypoints").entrySet()) {
                    for (var entry : category.getValue().getAsJsonArray()) {
                        var value = entry.isJsonObject() ? entry.getAsJsonObject().get("value") : entry;
                        String owner = value.getAsString().split("::", 2)[0];
                        classes.put(owner.replace('.', '/'), "Fabric entrypoint");
                    }
                }
            }
            if (mod.has("languageAdapters")) {
                for (var adapter : mod.getAsJsonObject("languageAdapters").entrySet())
                    classes.put(adapter.getValue().getAsString().replace('.', '/'), "Fabric language adapter");
            }
            if (mod.has("mixins")) {
                for (JsonElement config : mod.getAsJsonArray("mixins")) {
                    String path = config.isJsonObject()
                        ? config.getAsJsonObject().get("config").getAsString() : config.getAsString();
                    JsonObject mixin = object(jar, path);
                    if (mixin.has("plugin"))
                        classes.put(mixin.get("plugin").getAsString().replace('.', '/'), "Mixin plugin");
                    String prefix = mixin.has("package") ? mixin.get("package").getAsString() + "." : "";
                    for (String group : new String[]{"mixins", "client", "server"}) {
                        if (!mixin.has(group)) continue;
                        for (JsonElement name : mixin.getAsJsonArray(group))
                            classes.put((prefix + name.getAsString()).replace('.', '/'), "Mixin class");
                    }
                }
            }
            if (mod.has("accessWidener")) {
                String path = mod.get("accessWidener").getAsString();
                byte[] data = jar.get(path);
                if (data == null) throw new IOException("Missing Fabric access widener " + path);
                if (data.length > 1024 * 1024) throw new IOException("Oversized Fabric access widener " + path);
                String text = new String(data, StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    String[] words = line.split("#", 2)[0].strip().split("\\s+");
                    if (words.length >= 3 && !words[0].equals("accessWidener"))
                        classes.put(words[2], "Fabric access widener target");
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid Fabric metadata: " + e.getMessage(), e);
        }
        return classes;
    }

    private static JsonObject object(Map<String, byte[]> jar, String name) throws IOException {
        byte[] data = jar.get(name);
        if (data == null || data.length > 1024 * 1024)
            throw new IOException("Missing or oversized Fabric metadata " + name);
        return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
