/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.osmbuildings.tileserver;

import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.internal.bind.TypeAdapters;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

/**
 *
 * @author sbodmer
 */
public class JsonPrimitiveTypeAdapter extends TypeAdapter {

    @Override
    public void write(JsonWriter writer, Object t) throws IOException {
        // System.out.println("WRITE:"+t);
        JsonPrimitive prim = (JsonPrimitive) t;
        if (prim.isNumber()) {
            writer.value(prim.getAsNumber());
            return;

        } else if (prim.isBoolean()) {
            writer.value(prim.getAsBoolean());
            return;

        } else if (prim.isString()) {
            writer.value(prim.getAsString());
            
        } else if (prim.isJsonArray()) {
            System.out.println("==================!!! IS ARRAY");
            
        } else if (prim.isJsonObject()) {
            System.out.println("==================!!! IS OBJECT");
            
        }
        

    }

    @Override
    public Object read(JsonReader reader) throws IOException {
        return null;
    }
    
}
