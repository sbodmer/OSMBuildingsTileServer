/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.osmbuildings.tileserver;

import com.github.filosganga.geogson.gson.GeometryAdapterFactory;
import com.github.filosganga.geogson.model.Feature;
import com.github.filosganga.geogson.model.FeatureCollection;
import com.github.filosganga.geogson.model.Geometry;
import com.github.filosganga.geogson.model.LineString;
import com.github.filosganga.geogson.model.LinearRing;
import com.github.filosganga.geogson.model.Point;
import com.github.filosganga.geogson.model.Polygon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;
import de.westnordost.osmapi.OsmConnection;
import de.westnordost.osmapi.common.errors.OsmApiReadResponseException;
import de.westnordost.osmapi.map.MapDataDao;
import de.westnordost.osmapi.map.data.BoundingBox;
import de.westnordost.osmapi.map.data.Element.Type;
import de.westnordost.osmapi.map.data.LatLon;
import de.westnordost.osmapi.map.data.Node;
import de.westnordost.osmapi.map.data.Relation;
import de.westnordost.osmapi.map.data.RelationMember;
import de.westnordost.osmapi.map.data.Way;
import de.westnordost.osmapi.map.handler.MapDataHandler;
import java.awt.geom.Path2D;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

/**
 *
 * @author sbodmer
 */
public class OSMBuildingsHttpConnection extends Thread implements MapDataHandler {

    /**
     * Http message code database (code,text)
     */
    public static HashMap<String, String> messages = null;
    /**
     * The MIME type database (extension,mime)
     */
    public static HashMap<String, String> mimes = null;

    // protected long debugId = 157001066; 279536630
    // protected long debugId = 157001071L; 157001071
    // protected long debugId = 213847700;
    protected long debugId = 0;

    protected SimpleDateFormat format = null;

    protected OSMBuildingsTileServer server = null;
    protected BufferedReader in = null;
    protected DataOutputStream out = null;
    protected Socket socket = null;

    protected String method = "";
    protected String uri = "";
    protected String protocol = "";
    protected boolean store = true;

    protected Properties headers = new Properties();      //--- Request headers
    protected Properties params = new Properties();       //--- Request parameters

    /**
     * The nodes list, the key is the node id
     */
    protected HashMap<Long, Node> nodes = new HashMap<>();
    protected HashMap<Long, Way> ways = new HashMap<>();
    protected HashMap<Long, Relation> relations = new HashMap<>();

    protected Gson gson = null;

    static {
        //--- Fill the HTTP code messages
        messages = new HashMap<String, String>();
        messages.put("" + HttpURLConnection.HTTP_OK, "Ok");
        messages.put("" + HttpURLConnection.HTTP_FORBIDDEN, "Forbidden");
        messages.put("" + HttpURLConnection.HTTP_NOT_FOUND, "Not Found");
        messages.put("" + HttpURLConnection.HTTP_BAD_REQUEST, "Bad Request");
        messages.put("" + HttpURLConnection.HTTP_INTERNAL_ERROR, "Internal Server Error");
        messages.put("" + HttpURLConnection.HTTP_NOT_IMPLEMENTED, "Not Implemented");

        mimes = new HashMap<String, String>();
        mimes.put("html", "text/html");
        mimes.put("text", "text/plain");
        mimes.put("xml", "text/xml; charset=UTF-8");
        mimes.put("json", "application/json");

    }

    public OSMBuildingsHttpConnection(ThreadGroup tg, Socket socket, OSMBuildingsTileServer server) {
        super(tg, "" + socket.getInetAddress().getHostAddress());
        this.socket = socket;
        this.server = server;

        format = new SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));

        try {
            //--- Open I/O
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new DataOutputStream(socket.getOutputStream());

        } catch (IOException ex) {
            ex.printStackTrace();
        }

        //--- Register GeoJSon and primitive adapter to outpout correct properties
        //---  properties : {
        //---                 height: 20
        //---               }
        //--- instead of
        //---  properties : {
        //---                 height: { "value" : 20 }
        //---               }
        gson = new GsonBuilder()
                // .registerTypeAdapterFactory(new JtsAdapterFactory())
                .registerTypeAdapterFactory(new GeometryAdapterFactory())
                .serializeNulls()
                .registerTypeAdapter(JsonPrimitive.class, new JsonPrimitiveTypeAdapter())
                .create();

    }

    //**************************************************************************
    //*** Protected
    //**************************************************************************
    protected void parseHeaders() {
        //------------------------------------------------------------------------
        //--- Parse the Http request to retreive the parameters
        //------------------------------------------------------------------------
        try {
            String line = in.readLine();
            String tokens[] = line.split(" ");
            //--- First method
            method = tokens[0];
            if (tokens.length > 1) uri = tokens[1];
            try {
                uri = URLDecoder.decode(tokens[1], "ISO-8859-1");

            } catch (IllegalArgumentException ex) {
                //--- Already decoded

            } catch (ArrayIndexOutOfBoundsException ex) {
                //--- No URI passed

            }
            int marker = uri.indexOf('?');
            if (marker >= 0) {
                String pm = uri.substring(marker + 1);
                String parts[] = pm.split("&");
                for (int i = 0; i < parts.length; i++) {
                    // System.out.println("param:"+tokens[i]);
                    int index = parts[i].indexOf('=');
                    if (index == -1) continue;

                    String param = parts[i].substring(0, index).trim(); //ISO-8859-1
                    String value = parts[i].substring(index + 1);

                    if (index >= 0) params.put(param, value);
                }
                uri = uri.substring(0, marker);
            }
            //--- The protocol version, check if passed (some agent doesn not pass the protocol...)
            if (tokens.length >= 3) protocol = tokens[2];

            //--- Headers (first read empty line)
            // line = in.readLine();
            while (true) {
                line = in.readLine();
                // System.out.println("Header line:"+line);
                if ((line == null) || line.equals("")) break;
                int index = line.indexOf(':');
                String name = line.substring(0, index).trim();
                String value = line.substring(index + 1).trim();
                //--- Check if a cookie is present for sessionid
                if (name.equals("Cookie")) {
                    //--- A cookie was, found, add the values to the parameters
                    tokens = value.split(";");
                    for (int i = 0; i < tokens.length; i++) {
                        String tokens2[] = tokens[i].split("=");
                        //--- The trim is important
                        params.put(tokens2[0].trim(), tokens2[1].trim());
                    }

                } else {
                    // System.out.println("Header found:"+name+" = "+value);
                    headers.put(name, value);
                }

            }
            //--- If the method is POST, there may be parameters in data section, too
            if (method.equalsIgnoreCase("POST")) {
                //--- Params is body not supported

            } else if (method.equalsIgnoreCase("GET")) {
                //--- No body part
            } else if (method.equalsIgnoreCase("HEAD")) {
                //--- Do nothing
            }

        } catch (ArrayIndexOutOfBoundsException ex) {
            ex.printStackTrace();

        } catch (StringIndexOutOfBoundsException ex) {
            ex.printStackTrace();

        } catch (NullPointerException ex) {
            ex.printStackTrace();

        } catch (IOException ex) {
            ex.printStackTrace();

        }

    }

    /**
     * Produce building
     *
     * @param w
     * @return
     */
    protected Feature produceBuilding(Way w, ArrayList<Way> holes, double dminheight, double dheight) {
        ArrayList<Point> points = new ArrayList<>();
        List<Long> list = w.getNodeIds();
        Geometry geo = null;
        // System.out.println("WAY ID:" + w.getId() + " Points:" + list.size());
        for (int i = 0; i < list.size(); i++) {
            long id = list.get(i);
            Node n = nodes.get(id);
            LatLon ll = n.getPosition();
            Point p = Point.from(ll.getLongitude(), ll.getLatitude());
            points.add(p);
        }
        if (points.size() >= 4) {
            try {
                //--- Check if correct linear ring (first = last)
                // System.out.println("Checking ring for " + w.getId());
                // for (int j = 0; j < list.size(); j++) System.out.println("[" + w.getId() + "] Point " + j + " => " + list.get(j));
                Point first = points.get(0);
                Point last = points.get(points.size() - 1);
                // if (!first.equals(last)) points.add(first);
                // while (points.size() < 4) points.add(first);
                LinearRing outline = LinearRing.of(points);

                //--- Find holes
                ArrayList<LinearRing> h = new ArrayList<>();
                for (int i = 0; i < holes.size(); i++) {
                    points = new ArrayList<>();
                    list = holes.get(i).getNodeIds();
                    // System.out.println("WAY ID:" + w.getId() + " Points:" + list.size());
                    for (int j = 0; j < list.size(); j++) {
                        long id = list.get(j);
                        Node n = nodes.get(id);
                        LatLon ll = n.getPosition();
                        Point p = Point.from(ll.getLongitude(), ll.getLatitude());
                        points.add(p);
                    }
                    first = points.get(0);
                    last = points.get(points.size() - 1);
                    // if (!first.equals(last)) points.add(first);
                    // while (points.size() < 4) points.add(first);
                    LinearRing hole = LinearRing.of(points);
                    h.add(hole);
                }
                geo = Polygon.of(outline, h);

            } catch (IllegalArgumentException ex) {
                //--- Not a ring (first != last), do nothing
                geo = LineString.of(points);

            }

        } else {
            // System.out.println("[" + w.getId() + "] SIZE:" + points.size())
            geo = LineString.of(points);

        }
        //--- Find the properties
        HashMap<String, JsonElement> properties = new HashMap<>();
        properties.put("height", new JsonPrimitive(dheight));
        properties.put("minHeight", new JsonPrimitive(dminheight));
        Map<String, String> tags = w.getTags();
        if (tags != null) {
            Iterator<String> it = tags.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                String value = tags.get(key);
                //---Normlaize properties
                try {
                    if (key.equals("height")) {
                        value = value.replace('m', ' ').replace('M', ' ');
                        properties.put("height", new JsonPrimitive(Double.parseDouble(value)));

                    } else if (key.equals("levels")
                            || key.equals("building:levels")) {
                        properties.put("levels", new JsonPrimitive(Double.parseDouble(value)));

                    } else if (key.equals("building:min_level")) {
                        properties.put("minLevel", new JsonPrimitive(Double.parseDouble(value)));

                    } else if (key.equals("building:shape")) {
                        properties.put("shape", new JsonPrimitive(value));

                    } else if (key.equals("min_height")) {
                        value = value.replace('m', ' ').replace('M', ' ');
                        properties.put("minHeight", new JsonPrimitive(Double.parseDouble(value)));

                    } else if (key.equals("color")
                            || key.equals("colour")
                            || key.equals("building:colour")
                            || key.equals("building:color")) {
                        properties.put("color", new JsonPrimitive(value));

                    } else if (key.equals("material")
                            || key.equals("building:material")) {
                        properties.put("material", new JsonPrimitive(value));

                    } else if (key.equals("roof:shape")) {
                        properties.put("roofShape", new JsonPrimitive(value));

                    } else if (key.equals("roof:height")) {
                        value = value.replace('m', ' ').replace('M', ' ');
                        properties.put("roofHeight", new JsonPrimitive(Double.parseDouble(value)));

                    } else if (key.equals("roof:levels")) {
                        properties.put("roofLevels", new JsonPrimitive(Double.parseDouble(value)));

                    } else if (key.equals("roof:orientation")) {
                        properties.put("roofOrientation", new JsonPrimitive(value));

                    } else if (key.equals("roof:direction")) {
                        properties.put("roofDirection", new JsonPrimitive(Double.parseDouble(value)));

                    } else if (key.equals("roof:colour")
                            || key.equals("roof:color")) {
                        properties.put("roofColor", new JsonPrimitive(value));

                    } else if (key.equals("roof:material")) {
                        properties.put("roofMaterial", new JsonPrimitive(value));

                    } else if (key.equals("roof:angle")) {
                        properties.put("roofAngle", new JsonPrimitive(Double.parseDouble(value)));

                    } else {
                        // properties.put(key, new JsonPrimitive(value));
                        // System.out.println("NOT SUPPORTED TAG " + key + "=" + value);
                    }

                } catch (NumberFormatException ex) {
                    System.out.println("ERROR:" + key + "=" + value);
                    // ex.printStackTrace();
                }

            }
        }
        Feature f = Feature.builder()
                .withId("" + w.getId())
                .withGeometry(geo)
                .withProperties(properties)
                .build();

        String json = gson.toJson(f);
        // System.out.println("JSON:" + json);
        return f;
    }

    /**
     *
     * @param zoom
     * @param x
     * @param yson
     */
    protected void osmbuildings_debug(int zoom, int x, int y) throws OsmApiReadResponseException {
        System.out.println("(osmbuildings@" + getName() + ") " + zoom + "," + x + "," + y);

        //TODO: Check if already in cache
        String provider = server.getApiProvider();
        // https://api.openstreetmap.org/api/0.6/

        OsmConnection osm = new OsmConnection(provider, "OSMBuildingsHttpConnection");

        //--- Top left
        double tllon = Helpers.x2lon(x, zoom);
        double tllat = Helpers.y2lat(y, zoom);
        //--- Bottom right
        double brlon = Helpers.x2lon(x + 1, zoom);
        double brlat = Helpers.y2lat(y + 1, zoom);

        ArrayList<Feature> features = new ArrayList<>();

        // System.out.println("LAT:"+lat);
        // System.out.println("LON:"+lon);
        MapDataDao md = new MapDataDao(osm);
        // LSI Media way ID 195914322
        // BoundingBox bounds = new BoundingBox(46.1927375, 6.1282588, 46.1939064, 6.1297409);
        BoundingBox bounds = new BoundingBox(brlat, tllon, tllat, brlon);
        //--- Fill the data
        md.getMap(bounds, this);

        if (debugId != 0) dumpWay(debugId);

        //-------------------------
        //--- Handle relation first
        //-------------------------
        /*
        Iterator<Relation> rit = relations.values().iterator();
        while (rit.hasNext()) {
            Relation r = rit.next();
            Map<String, String> tags = r.getTags();
            if (tags == null) continue;

            boolean process = false;
            String type = tags.get("type");
            if (type == null) continue;
            if (type.equals("multipolygon")) process = true;
            if (type.equals("building")) process = true;
            if (tags.get("building") != null) process = true;
            if (!process) continue;

            System.out.println("[RELATION] Processing building relation");
            //--- The first is the outer one
            ArrayList<Node> building = new ArrayList<>();

            List<RelationMember> list = r.getMembers();
            for (int i = 0; i < list.size(); i++) {
                RelationMember rm = list.get(i);
                // System.out.println("Relation : "+rm.getType()+", "+rm.getRole()+", "+rm.getRef());
                Node n = nodes.get(rm.getRef());
                if (n != null) {
                    System.out.println("FOUND Node:" + n.getId() + " role:" + rm.getRole());
                }

            }

        }
         */
        //----------------------------------------------------------
        //--- Handle buildings ways
        //--- Find the all the parts for buldings
        //----------------------------------------------------------
        Iterator<Way> wit = ways.values().iterator();
        ArrayList<Way> outlines = new ArrayList<>();    //--- All buldings
        ArrayList<Way> parts = new ArrayList<>();       //--- All building parts
        while (wit.hasNext()) {
            Way w = wit.next();
            Map<String, String> tags = w.getTags();
            if (tags == null) continue;
            if (tags.get("building") != null) {
                String value = tags.get("building");
                outlines.add(w);

            } else if (tags.get("building:part") != null) {
                parts.add(w);
            }
        }

        //--- For each building, find the parts
        while (outlines.size() > 0) {
            Way main = outlines.remove(0);

            //--- The list of included parts
            ArrayList<Way> included = new ArrayList<>();

            //--- Create the outling polygone
            Path2D path = producePath(main);

            //--- Iterate through all parts to find the one included in the
            //--- the main outline building
            for (int j = 0; j < parts.size(); j++) {
                Way pw = parts.get(j);
                List<Long> list = pw.getNodeIds();
                boolean in = true;
                for (int k = 0; k < list.size(); k++) {
                    long id = list.get(k);
                    Node n = nodes.get(id);
                    LatLon ll = n.getPosition();
                    if (!path.contains(ll.getLongitude(), ll.getLatitude())) {
                        in = false;
                        break;
                    }
                }
                if (in) included.add(pw);

            }

            if (included.isEmpty()) {
                //--- Produce the main building
                Feature f = produceBuilding(main, new ArrayList<>(), 0, 0);
                features.add(f);

            } else {
                //-----------------------------------------------------------------
                //--- For each included parts, find if there are holes
                //------------------------------------------------------------------
                while (included.size() > 0) {
                    Way pw = included.remove(0);

                    //--- Store the holes (parts which are included in part)
                    ArrayList<Way> holes = new ArrayList<>();

                    //--- Store part height, only same height can be holes
                    Map<String, String> tags = pw.getTags();
                    int height = 0;
                    if (tags != null) {
                        try {
                            height = Integer.parseInt(tags.get("height"));

                        } catch (NumberFormatException ex) {
                            //---
                        }
                    }

                    ArrayList<Way> processed = new ArrayList<>();

                    //--- Check against the rest of the parts
                    for (int j = 0; j < included.size(); j++) {
                        Way tpw = included.get(j);
                        tags = tpw.getTags();
                        int theight = 0;
                        if (tags != null) {
                            try {
                                theight = Integer.parseInt(tags.get("height"));

                            } catch (NumberFormatException ex) {
                                //---
                            }
                        }
                        //--- If different size, no holes possible
                        if (theight != height) continue;

                        //--- Check if other part is included in current part
                        List<Long> list = tpw.getNodeIds();
                        boolean in = true;
                        for (int k = 0; k < list.size(); k++) {
                            long id = list.get(k);
                            Node n = nodes.get(id);
                            LatLon ll = n.getPosition();
                            if (!path.contains(ll.getLongitude(), ll.getLatitude())) {
                                in = false;
                                break;
                            }
                        }
                        if (in) {
                            //--- Bingo, we have a hole
                            holes.add(tpw);
                            //--- Remove the part, it was already handled
                            processed.add(tpw);
                        }

                    }

                    Feature f = produceBuilding(pw, holes, 0, 0);
                    features.add(f);

                    //--- remove the process parts
                    while (processed.size() > 0) {
                        Way pr = processed.remove(0);
                        included.remove(pr);
                        parts.remove(pr);
                    }

                    //--- Remove from global list
                    parts.remove(pw);
                }

            }

        }

        //--------------------------------------------------
        //--- Handle the other part outside of the outlines
        //--------------------------------------------------
        System.out.println("PARTS TO HANDLE:" + parts.size());
        for (int i = 0; i < parts.size(); i++) {
            ArrayList<Way> holes = new ArrayList<>();
            features.add(produceBuilding(parts.get(i), holes, 0, 0));

        }

        //----------------------------------------------------
        //--- Handle outline 
        //-----------------------------------------------------
        System.out.println("OUTLINE TO HANDLE:" + outlines.size());
        for (int i = 0; i < outlines.size(); i++) {
            ArrayList<Way> holes = new ArrayList<>();
            features.add(produceBuilding(outlines.get(i), holes, 0, 0));

        }

        FeatureCollection coll = new FeatureCollection(features);
        String json = gson.toJson(coll);

        try {
            File f = new File(server.getCachePath(), "" + zoom + "" + File.separatorChar + "" + x + "" + File.separatorChar + "" + y + ".json");
            System.out.println("(osmbuildings@" + getName() + ") Saving produced json to " + f.getPath());
            f.getParentFile().mkdirs();
            FileWriter w = new FileWriter(f);
            w.write(json);
            w.close();

        } catch (IOException ex) {
            ex.printStackTrace();
        }
        sendHttpResponse(HttpURLConnection.HTTP_OK, mimes.get("json"), null, json.getBytes());

        // System.out.println("JSON:" + json);
        System.out.println("(osmbuildings@" + getName() + ") Finished");
    }

    protected void processBuildingsParts(ArrayList<Feature> features) {
        //----------------------------------------------------------------------
        //--- 2. Handle the buildings tags with parts
        //----------------------------------------------------------------------
        Iterator<Way> wit = ways.values().iterator();
        ArrayList<Way> outlines = new ArrayList<>();    //--- All buldings
        ArrayList<Way> parts = new ArrayList<>();       //--- All building parts
        while (wit.hasNext()) {
            Way w = wit.next();
            Map<String, String> tags = w.getTags();
            if (tags == null) continue;
            if (tags.get("building") != null) {
                String value = tags.get("building");
                outlines.add(w);

            } else if (tags.get("building:part") != null) {
                parts.add(w);
            }
        }

        //--- For each building, find the parts
        while (outlines.size() > 0) {
            Way main = outlines.remove(0);

            Map<String, String> tags = main.getTags();
            double height = 0;
            if (tags.get("height") != null) height = Double.parseDouble(tags.get("height").replace('m', ' '));

            //--- The list of included parts (which will be holes)
            ArrayList<Way> included = new ArrayList<>();
            //--- The included will always be a hole, if the included
            //--- is lower than main height, add a new column in the
            //--- hole
            ArrayList<Way> columns = new ArrayList<>();

            //--- Create the outling polygone
            Path2D path = producePath(main);

            //--- Iterate through all parts to find the one included in the
            //--- the main outline building
            for (int j = 0; j < parts.size(); j++) {
                Way pw = parts.get(j);
                List<Long> list = pw.getNodeIds();
                boolean in = true;
                for (int k = 0; k < list.size(); k++) {
                    long id = list.get(k);
                    Node n = nodes.get(id);
                    LatLon ll = n.getPosition();
                    if (!path.contains(ll.getLongitude(), ll.getLatitude())) {
                        in = false;
                        break;
                    }
                }
                if (in) {
                    //--- Find the included part height
                    tags = main.getTags();
                    double pheight = 0;
                    if (tags.get("height") != null) pheight = Double.parseDouble(tags.get("height"));
                    if (pheight != height) {
                        System.out.println("Holes have different size :" + pw.getId() + " " + height + " <> " + pheight);
                        columns.add(pw);

                    } else {
                        // System.out.println("[" + main.getId() + "] includes " + pw.getId());
                        included.add(pw);
                    }
                }

            }

            //--- Produce the main building (with holes)
            Feature f = produceBuilding(main, included, 0, 0);
            features.add(f);

            //--- Produce the additional columns
            for (int i = 0; i < columns.size(); i++) {
                features.add(produceBuilding(columns.get(i), new ArrayList<>(), 0, 0));

            }

            //--- Remove the already processed parts
            for (int i = 0; i < included.size(); i++) parts.remove(included.get(i));
            for (int i = 0; i < columns.size(); i++) parts.remove(columns.get(i));
        }
    }

    /**
     * Handle relations, parts
     *
     * @param zoom
     * @param x
     * @param y
     * @throws OsmApiReadResponseException
     */
    protected void osmbuildings(int zoom, int x, int y) throws OsmApiReadResponseException {
        System.out.println("(D) osmbuildings@" + getName() + " " + zoom + "," + x + "," + y);

        String provider = server.getApiProvider();
        // https://api.openstreetmap.org/api/0.6/
        OsmConnection osm = new OsmConnection(provider, "OSMBuildingsHttpConnection");
        osm.setOutputCall(true);
        //--- Top left
        double tllon = Helpers.x2lon(x, zoom);
        double tllat = Helpers.y2lat(y, zoom);
        //--- Bottom right
        double brlon = Helpers.x2lon(x + 1, zoom);
        double brlat = Helpers.y2lat(y + 1, zoom);
        double dLat = tllat - brlat;
        double dLon = brlon - tllon;
        double fac = 0;
        ArrayList<Feature> features = new ArrayList<>();

        // System.out.println("LAT:"+lat);
        // System.out.println("LON:"+lon);
        MapDataDao md = new MapDataDao(osm);
        // LSI Media way ID 195914322
        // BoundingBox bounds = new BoundingBox(46.1927375, 6.1282588, 46.1939064, 6.1297409);
        BoundingBox bounds = new BoundingBox(brlat - (fac * dLat), tllon - (fac * dLon), tllat + (fac * dLat), brlon + (fac * dLon));
        //--- Fill the data
        md.getMap(bounds, this);

        //--- To avoid double processing (by relation and parts)
        ArrayList<Long> alreadyProcessed = new ArrayList<>();

        // if (debugId != 0) dumpWay(debugId);
        //----------------------------------------------------------------------
        //--- Find the way,node,relation which are missing in this tile to
        //--- have the full data to create buldings
        //----------------------------------------------------------------------
        //--- Call until no more to fetch
        while (isInterrupted() == false) {
            boolean nf = fetchMissingObjects(osm);
            if (nf == false) break;

        }

        //----------------------------------------------------------------------
        //--- 1. Handle relation first
        //----------------------------------------------------------------------
        processRelations(features, alreadyProcessed);

        //----------------------------------------------------------------------
        //--- 2. Handle the buildings tags with parts
        //----------------------------------------------------------------------
        processWays(features, alreadyProcessed);

        FeatureCollection coll = new FeatureCollection(features);
        String json = gson.toJson(coll);
        if (store) {
            try {
                File f = new File(server.getCachePath(), "" + zoom + "" + File.separatorChar + "" + x + "" + File.separatorChar + "" + y + ".json");
                System.out.println("(D) osmbuildings@" + getName() + " Saving produced json to " + f.getPath());
                f.getParentFile().mkdirs();
                FileWriter w = new FileWriter(f);
                w.write(json);
                w.close();

            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        sendHttpResponse(HttpURLConnection.HTTP_OK, mimes.get("json"), null, json.getBytes());

        // System.out.println("JSON:" + json);
        System.out.println("(D) osmbuildings@" + getName() + " Finished");
    }

    /**
     * The outline building will be rendererd with all included parts as holes
     *
     * DEBUG ONLY
     * 
     * @param zoom
     * @param x
     * @param y
     * @throws OsmApiReadResponseException
     */
    protected void osmbuildings3(int zoom, int x, int y) throws OsmApiReadResponseException {
        System.out.println("(osmbuildings@" + getName() + ") " + zoom + "," + x + "," + y);

        //TODO: Check if already in cache
        String provider = server.getApiProvider();
        // https://api.openstreetmap.org/api/0.6/

        OsmConnection osm = new OsmConnection(provider, "OSMBuildingsHttpConnection");

        //--- Top left
        double tllon = Helpers.x2lon(x, zoom);
        double tllat = Helpers.y2lat(y, zoom);
        //--- Bottom right
        double brlon = Helpers.x2lon(x + 1, zoom);
        double brlat = Helpers.y2lat(y + 1, zoom);

        ArrayList<Feature> features = new ArrayList<>();

        // System.out.println("LAT:"+lat);
        // System.out.println("LON:"+lon);
        MapDataDao md = new MapDataDao(osm);
        // LSI Media way ID 195914322
        // BoundingBox bounds = new BoundingBox(46.1927375, 6.1282588, 46.1939064, 6.1297409);
        BoundingBox bounds = new BoundingBox(brlat, tllon, tllat, brlon);
        //--- Fill the data
        md.getMap(bounds, this);

        if (debugId != 0) dumpWay(debugId);

        //----------------------------------------------------------
        //--- Handle buildings ways
        //--- Find the all the parts for buldings
        //----------------------------------------------------------
        Iterator<Way> wit = ways.values().iterator();
        ArrayList<Way> outlines = new ArrayList<>();    //--- All buldings
        ArrayList<Way> parts = new ArrayList<>();       //--- All building parts
        while (wit.hasNext()) {
            Way w = wit.next();
            Map<String, String> tags = w.getTags();
            if (tags == null) continue;
            if (tags.get("building") != null) {
                String value = tags.get("building");
                outlines.add(w);

            } else if (tags.get("building:part") != null) {
                parts.add(w);
            }
        }

        //--- For each building, find the parts
        while (outlines.size() > 0) {
            Way main = outlines.remove(0);

            Map<String, String> tags = main.getTags();
            double height = 0;
            if (tags.get("height") != null) height = Double.parseDouble(tags.get("height"));

            //--- The list of included parts (which will be holes)
            ArrayList<Way> included = new ArrayList<>();
            //--- The included will always be a hole, if the included
            //--- is greater or lower than main height, add a new column in the
            //--- hole
            ArrayList<Way> columns = new ArrayList<>();

            //--- Create the outling polygone
            Path2D path = producePath(main);

            //--- Iterate through all parts to find the one included in the
            //--- the main outline building
            for (int j = 0; j < parts.size(); j++) {
                Way pw = parts.get(j);
                List<Long> list = pw.getNodeIds();
                boolean in = true;
                for (int k = 0; k < list.size(); k++) {
                    long id = list.get(k);
                    Node n = nodes.get(id);
                    LatLon ll = n.getPosition();
                    if (!path.contains(ll.getLongitude(), ll.getLatitude())) {
                        in = false;
                        break;
                    }
                }
                if (in) {
                    System.out.println("[" + main.getId() + "] includes " + pw.getId());
                    included.add(pw);
                }

            }

            //--- Find for each included part if the height is the same
            for (int i = 0; i < included.size(); i++) {
                Way pw = parts.get(i);
                tags = main.getTags();
                double pheight = 0;
                if (tags.get("height") != null) pheight = Double.parseDouble(tags.get("height"));
                if (pheight != height) {
                    // System.out.println("Holes have different size :" + pw.getId() + " " + height + " <> " + pheight);
                    columns.add(pw);
                }

            }

            //--- Produce the main building (with holes)
            Feature f = produceBuilding(main, included, 0, 0);
            features.add(f);

            //--- Produce the additional columns
            for (int i = 0; i < columns.size(); i++) {
                features.add(produceBuilding(columns.get(i), new ArrayList<>(), 0, 0));

            }

            //--- Remove the already processed parts
            for (int i = 0; i < included.size(); i++) parts.remove(included.get(i));
            for (int i = 0; i < columns.size(); i++) parts.remove(columns.get(i));
        }

        //--------------------------------------------------
        //--- Handle the other part outside of the outlines
        //--------------------------------------------------
        System.out.println("PARTS TO HANDLE:" + parts.size());
        for (int i = 0; i < parts.size(); i++) {
            ArrayList<Way> holes = new ArrayList<>();
            features.add(produceBuilding(parts.get(i), holes, 0, 0));

        }

        //----------------------------------------------------
        //--- Handle outline 
        //-----------------------------------------------------
        /*
        System.out.println("OUTLINE TO HANDLE:" + outlines.size());
        for (int i = 0; i < outlines.size(); i++) {
            ArrayList<Way> holes = new ArrayList<>();
            features.add(produceBuilding(outlines.get(i), holes));

        }
         */
        FeatureCollection coll = new FeatureCollection(features);
        String json = gson.toJson(coll);

        try {
            File f = new File(server.getCachePath(), "" + zoom + "" + File.separatorChar + "" + x + "" + File.separatorChar + "" + y + ".json");
            System.out.println("(osmbuildings@" + getName() + ") Saving produced json to " + f.getPath());
            f.getParentFile().mkdirs();
            FileWriter w = new FileWriter(f);
            w.write(json);
            w.close();

        } catch (IOException ex) {
            ex.printStackTrace();
        }
        sendHttpResponse(HttpURLConnection.HTTP_OK, mimes.get("json"), null, json.getBytes());

        // System.out.println("JSON:" + json);
        System.out.println("(osmbuildings@" + getName() + ") Finished");
    }

    /**
     * Display all buildings
     * 
     * DEBUG ONLY
     *
     * @param zoom
     * @param x
     * @param y
     * @throws OsmApiReadResponseException
     */
    protected void osmbuildings4(int zoom, int x, int y) throws OsmApiReadResponseException {
        System.out.println("(osmbuildings@" + getName() + ") " + zoom + "," + x + "," + y);

        //TODO: Check if already in cache
        String provider = server.getApiProvider();
        // https://api.openstreetmap.org/api/0.6/

        OsmConnection osm = new OsmConnection(provider, "OSMBuildingsHttpConnection");

        //--- Top left
        double tllon = Helpers.x2lon(x, zoom);
        double tllat = Helpers.y2lat(y, zoom);
        //--- Bottom right
        double brlon = Helpers.x2lon(x + 1, zoom);
        double brlat = Helpers.y2lat(y + 1, zoom);

        ArrayList<Feature> features = new ArrayList<>();

        // System.out.println("LAT:"+lat);
        // System.out.println("LON:"+lon);
        MapDataDao md = new MapDataDao(osm);
        // LSI Media way ID 195914322
        // BoundingBox bounds = new BoundingBox(46.1927375, 6.1282588, 46.1939064, 6.1297409);
        BoundingBox bounds = new BoundingBox(brlat, tllon, tllat, brlon);
        //--- Fill the data
        md.getMap(bounds, this);

        if (debugId != 0) dumpWay(debugId);

        //----------------------------------------------------------
        //--- Handle buildings ways
        //--- Find the all the parts for buldings
        //----------------------------------------------------------
        Iterator<Way> wit = ways.values().iterator();
        ArrayList<Way> outlines = new ArrayList<>();    //--- All buldings
        ArrayList<Way> parts = new ArrayList<>();       //--- All building parts
        while (wit.hasNext()) {
            Way w = wit.next();
            Map<String, String> tags = w.getTags();
            if (tags == null) continue;
            if (tags.get("building") != null) {
                String value = tags.get("building");
                outlines.add(w);

            } else if (tags.get("building:part") != null) {
                parts.add(w);
            }
        }

        //--------------------------------------------------
        //--- Handle the other part outside of the outlines
        //--------------------------------------------------
        System.out.println("PARTS TO HANDLE:" + parts.size());
        for (int i = 0; i < parts.size(); i++) {
            ArrayList<Way> holes = new ArrayList<>();
            features.add(produceBuilding(parts.get(i), holes, 0, 0));

        }

        //----------------------------------------------------
        //--- Handle outline 
        //-----------------------------------------------------
        System.out.println("OUTLINE TO HANDLE:" + outlines.size());
        for (int i = 0; i < outlines.size(); i++) {
            ArrayList<Way> holes = new ArrayList<>();
            features.add(produceBuilding(outlines.get(i), holes, 0, 0));

        }

        FeatureCollection coll = new FeatureCollection(features);
        String json = gson.toJson(coll);

        try {
            File f = new File(server.getCachePath(), "" + zoom + "" + File.separatorChar + "" + x + "" + File.separatorChar + "" + y + ".json");
            System.out.println("(osmbuildings@" + getName() + ") Saving produced json to " + f.getPath());
            f.getParentFile().mkdirs();
            FileWriter w = new FileWriter(f);
            w.write(json);
            w.close();

        } catch (IOException ex) {
            ex.printStackTrace();
        }
        sendHttpResponse(HttpURLConnection.HTTP_OK, mimes.get("json"), null, json.getBytes());

        // System.out.println("JSON:" + json);
        System.out.println("(osmbuildings@" + getName() + ") Finished");
    }

    /**
     * Send back some response
     *
     * @param status
     * @param mime
     * @param headers
     * @param data
     */
    protected void sendHttpResponse(int status, String mime, Properties headers, byte data[]) {
        try {
            out.writeBytes("HTTP/1.0 " + status + " " + messages.get("" + status) + " \r\n");

            if (mime != null) out.writeBytes("Content-Type: " + mime + "; charset=UTF-8\r\n");
            if (data != null) out.writeBytes("Content-Length: " + data.length + "\r\n");
            if ((headers == null) || (headers.getProperty("Date") == null)) out.writeBytes("Date: " + format.format(new Date()) + "\r\n");

            if (headers != null) {
                Enumeration e = headers.keys();
                while (e.hasMoreElements()) {
                    String key = (String) e.nextElement();
                    String value = headers.getProperty(key);
                    out.writeBytes(key + ": " + value + "\r\n");
                }
            }
            out.writeBytes("\r\n");
            out.flush();

            if (data != null) out.write(data);
            out.flush();

        } catch (IOException ex) {
            //---
        }
    }

    //**************************************************************************
    //*** MapDataHandler
    //**************************************************************************
    @Override
    public void handle(BoundingBox bounds) {
        System.out.println("BoundingBox:" + bounds.getAsLeftBottomRightTopString());
    }

    @Override
    public void handle(Node node) {
        nodes.put(node.getId(), node);
        if (node.getId() == debugId) System.out.println("FOUND NODE:" + node);
        /*
        Map<String, String> tags = node.getTags();
        if (tags != null) {
            Iterator<String> it = tags.keySet().iterator();
            System.out.print("Node => ");
            while (it.hasNext()) {
                String key = it.next();
                String value = tags.get(key);
                System.out.print(","+key+" = "+value);
            }
            System.out.println("");
            // System.out.println("Buildings:" + tags.get("building"));
            
        }
         */
        // System.out.println("Node:"+node);

    }

    /**
     * @param way
     */
    @Override
    public void handle(Way way) {
        ways.put(way.getId(), way);
        if (way.getId() == debugId) System.out.println("FOUND WAY[" + way.getId() + "]:" + way);

        /*
        Map<String, String> tags = way.getTags();
        if (tags != null) {
            if (tags.containsKey("building")) {
                String value = tags.get("building");
                if (value.equals("yes")) {
                    
                    List<Long> list = way.getNodeIds();
                    for (int i=0;i<list.size();i++) {
                        long nodeId = list.get(i);
                        Node node = nodes.get(nodeId);
                        LatLon ll = node.getPosition();
                        System.out.println("LatLon:"+ll.getLatitude()+"x"+ll.getLongitude());
                        
                    }
                }
            }
            System.out.print("Way => ");
            Iterator<String> it = tags.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                String value = tags.get(key);
                System.out.print(","+key+" = "+value);
            }
            System.out.println("");
            // System.out.println("Buildings:" + tags.get("building"));
        
        }
        // System.out.println("Way:" + way);
         */
    }

    /**
     * Filter by building related
     *
     * @param relation
     */
    @Override
    public void handle(Relation relation) {
        // System.out.println("handle["+relation.getId()+"]");
        relations.put(relation.getId(), relation);
        if (relation.getId() == debugId) System.out.println("FOUND RELATION:" + relation);
        // System.out.println("Relation:" + relation);
    }

    //**************************************************************************
    //*** Private
    //**************************************************************************
    private void dumpWay(long id) {
        Way w = ways.get(id);
        System.out.println(">>> WAY " + id + " " + w);
        Map<String, String> tags = w.getTags();
        if (tags != null) {
            Iterator<String> keys = tags.keySet().iterator();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = tags.get(key);
                System.out.println("" + key + "=" + value);
            }
        }
        List<Long> list = w.getNodeIds();
        System.out.println("Nodes:" + list.size());
        System.out.println("");
        for (int i = 0; i < list.size(); i++) {
            Node n = nodes.get(list.get(i));
            System.out.println("Node [" + list.get(i) + "] " + n);
        }
        System.out.println("<<<");
    }

    //--- Return an awt path to check if some points are inside a polygin
    private Path2D producePath(Way w) {
        Path2D path = new java.awt.geom.Path2D.Double();
        List<Long> list = w.getNodeIds();
        // System.out.println("WAY ID:" + w.getId() + " Points:" + list.size());
        for (int j = 0; j < list.size(); j++) {
            long id = list.get(j);
            Node n = nodes.get(id);
            LatLon ll = n.getPosition();
            Point p = Point.from(ll.getLongitude(), ll.getLatitude());
            if (j == 0) {
                path.moveTo(ll.getLongitude(), ll.getLatitude());

            } else {
                path.lineTo(ll.getLongitude(), ll.getLatitude());
            }
        }
        return path;
    }

    /**
     * Returns true if the child is included in the passed path
     *
     * @param parent
     * @param child
     * @return
     */
    private boolean isIncluded(Path2D path, Way child) {
        List<Long> list = child.getNodeIds();
        boolean in = true;
        for (int k = 0; k < list.size(); k++) {
            long id = list.get(k);
            Node n = nodes.get(id);
            LatLon ll = n.getPosition();
            if (!path.contains(ll.getLongitude(), ll.getLatitude())) {
                in = false;
                break;
            }
        }
        if (in) return true;
        return false;
    }

    /**
     * Merge the passed outer way if needed
     *
     * @param outer
     */
    private void mergeOuter(ArrayList<Way> outer) {
        for (int i = 0; i < outer.size(); i++) {
            Way w = outer.get(i);

            List<Long> nodeList = w.getNodeIds();
            long firstId = nodeList.get(0);
            long lastId = nodeList.get(nodeList.size() - 1);
            System.out.println("[" + w.getId() + "] first=" + firstId + ",last=" + lastId + " (" + nodeList.size() + " nodes)");
            if (firstId != lastId) {
                //--- Not a ring, find the next outer if linked
                for (int j = i + 1; j < outer.size(); j++) {
                    Way tmp = outer.get(j);
                    List<Long> tnodeList = tmp.getNodeIds();
                    long tfirstId = tnodeList.get(0);
                    long tlastId = tnodeList.get(tnodeList.size() - 1);
                    System.out.println("[" + tmp.getId() + "] first=" + tfirstId + ",last=" + tlastId + " (" + tnodeList.size() + " nodes)");
                    if ((tfirstId == lastId) && (firstId == tlastId)) {
                        //--- Ok linked
                        System.out.println("[" + w.getId() + "] is linked to [" + tmp.getId() + "]");
                        for (int k = 1; k < tnodeList.size(); k++) {
                            nodeList.add(tnodeList.get(k));
                        }
                        outer.remove(tmp);

                    }
                }

            }

        }
    }

    /**
     * Process the relations
     *
     * @param features
     * @param alreadyProcessed
     */
    private void processRelations(ArrayList<Feature> features, ArrayList<Long> alreadyProcessed) {
        Iterator<Relation> rit = relations.values().iterator();
        while (rit.hasNext()) {
            Relation r = rit.next();
            Map<String, String> tags = r.getTags();
            if (tags == null) continue;

            // if ((r.getId() != 6289395L) && (r.getId() != 6289395L)) continue;
            // if (r.getId() != 277924006L) continue;
            // if (r.getId() != 1359233L) continue;
            boolean process = false;
            String type = tags.get("type");
            String building = tags.get("building");
            String buildingPart = tags.get("building:part");
            if (building == null) building = "";
            if (buildingPart == null) buildingPart = "";
            if (type == null) continue;

            // if (type.equals("multipolygon")) process = true;
            //--- If building="yes" or type="building", then process relation
            if (type.equals("building")) process = true;
            if (!building.equals("")) process = true;
            if (!buildingPart.equals("")) process = true;
            
            if (!process) continue;

            //--- If no height, consider not to handled (only footprint information)
            //--- Default values for height an min height (needed for parts with
            //--- no height defined
            double height = 0;
            double minheight = 0;
            try {
                height = Double.parseDouble(tags.get("height") == null ? "0" : tags.get("height"));
                minheight = Double.parseDouble(tags.get("min_height") == null ? "0" : tags.get("min_height"));
                
            } catch (NumberFormatException ex) {
                //---
            }
            System.out.println("Relation handling [" + r.getId() + "] , min_height=" + minheight + ", height=" + height + " building=" + building + " building:part=" + buildingPart);
            //---Multiple outer way possible
            ArrayList<Way> outer = new ArrayList<>();
            ArrayList<Way> inner = new ArrayList<>();
            ArrayList<Way> parts = new ArrayList<>();

            List<RelationMember> list = r.getMembers();
            for (int i = 0; i < list.size(); i++) {
                RelationMember rm = list.get(i);
                // System.out.println("Relation : " + rm.getType() + ", " + rm.getRole() + ", " + rm.getRef());
                Node n = nodes.get(rm.getRef());
                // if (n != null) System.out.println("FOUND Node:" + n.getId());
                Way w = ways.get(rm.getRef());
                if (w != null) {
                    if (rm.getRole().equals("outer")) {
                        System.out.println("[" + r.getId() + "] found outer way:" + w.getId());
                        outer.add(w);

                    } else if (rm.getRole().equals("inner")) {
                        //--- Some inner path (holes)
                        System.out.println("[" + r.getId() + "] found inner way:" + w.getId());
                        inner.add(w);

                    } else if (rm.getRole().equals("part")) {
                        //--- Different parts
                        System.out.println("[" + r.getId() + "] found part way:" + w.getId());
                        parts.add(w);

                    } else if (rm.getRole().equals("outline")) {
                        System.out.println("[" + r.getId() + "] found outline way:" + w.getId());
                        //--- Outline of the part, do not render
                        Map<String, String> t = w.getTags();
                        if (t != null) {
                            //--- If building outiline is yes, then consider renderering
                            //--- only for special cases
                            String b = t.get("building");
                            if (b != null) {
                                if (b.equals("cathedral")) {
                                    //--- No rendering of the outline
                                } else if (b.equals("basilica")) {
                                    //--- No rendering of the outline (ex: Vaticn)
                                    
                                } else {
                                    //--- building is yes, check if it's a part
                                    String bp = t.get("building:part");
                                    if (bp != null) {
                                        if (bp.equals("no")) {
                                            //--- No rendering
                                            
                                        } else {
                                            parts.add(w);
                                        }
                                    }
                                    
                                }

                            }

                        }
                        alreadyProcessed.add(w.getId());

                    }

                }
                // Relation tr = relations.get(rm.getRef());
                // if (tr != null) System.out.println("FOUND RELATION:" + tr.getId());

            }

            //--- Merge outer with same first and last node
            //--- Store the origina list,so it can be reset to old list because
            //--- other component needs the origial one
            HashMap<Long, ArrayList<Long>> original = new HashMap<>();
            for (int i = 0; i < outer.size(); i++) {
                Way w = outer.get(i);

                List<Long> nodeList = w.getNodeIds();
                long firstId = nodeList.get(0);
                long lastId = nodeList.get(nodeList.size() - 1);
                System.out.println("[" + w.getId() + "] first=" + firstId + ",last=" + lastId + " (" + nodeList.size() + " nodes)");
                if (firstId != lastId) {
                    //--- Not a ring, find the next outer if linked
                    for (int j = i + 1; j < outer.size(); j++) {
                        Way tmp = outer.get(j);
                        List<Long> tnodeList = tmp.getNodeIds();
                        long tfirstId = tnodeList.get(0);
                        long tlastId = tnodeList.get(tnodeList.size() - 1);
                        System.out.println("[" + tmp.getId() + "] first=" + tfirstId + ",last=" + tlastId + " (" + tnodeList.size() + " nodes)");
                        if (tfirstId == lastId) {
                            //--- Ok linked
                            System.out.println("[" + w.getId() + "] is linked to [" + tmp.getId() + "]");
                            //--- Store old list
                            ArrayList<Long> old = new ArrayList<>();
                            original.put(w.getId(), old);
                            old.addAll(nodeList);
                            for (int k = 1; k < tnodeList.size(); k++) {
                                nodeList.add(tnodeList.get(k));
                            }
                            outer.remove(tmp);
                            i--;
                            break;

                        } else if (firstId == tlastId) {
                            //--- Ok linked
                            System.out.println("[" + w.getId() + "] is linked to [" + tmp.getId() + "]");
                            //--- Store old list
                            ArrayList<Long> old = new ArrayList<>();
                            original.put(w.getId(), old);
                            old.addAll(nodeList);
                            for (int k = 1; k < tnodeList.size(); k++) {
                                nodeList.add(tnodeList.get(k));
                            }
                            outer.remove(tmp);
                            i--;
                            break;
                        }
                    }

                }
                if (inner.isEmpty()) {
                    //--- If main building and no inner, consider not rendering it
                    if (!building.equals("")) outer.remove(w);
                }

            }
            for (int i = 0; i < outer.size(); i++) {
                Way tmp = outer.get(i);

                if (height == 0) {
                    //--- If the foot print has inner, consider render after all
                    if (inner.size() >= 1) {
                        features.add(produceBuilding(tmp, inner, minheight, height));
                        alreadyProcessed.add(tmp.getId());
                    }

                } else {
                    // System.out.println("Producing " + building.getId());
                    features.add(produceBuilding(tmp, inner, minheight, height));
                    alreadyProcessed.add(tmp.getId());
                }

            }

            //--- Reset to original nodes list
            Iterator<Long> it = original.keySet().iterator();
            while (it.hasNext()) {
                long id = it.next();
                Way w = ways.get(id);
                ArrayList<Long> nodeList = original.get(id);
                List<Long> nl = w.getNodeIds();
                nl.clear();
                nl.addAll(nodeList);

            }

            for (int j = 0; j < parts.size(); j++) {
                features.add(produceBuilding(parts.get(j), new ArrayList<>(), minheight, height));
            }
        }
    }

    private void processWays(ArrayList<Feature> features, ArrayList<Long> alreadyProcessed) {
        Iterator<Way> wit = ways.values().iterator();
        ArrayList<Way> outlines = new ArrayList<>();    //--- All buldings
        ArrayList<Way> parts = new ArrayList<>();       //--- All building parts
        while (wit.hasNext()) {
            Way w = wit.next();
            if (alreadyProcessed.contains(w.getId())) continue;

            Map<String, String> tags = w.getTags();
            if (tags == null) continue;
            String building = tags.get("building");
            String buildingPart = tags.get("building:part");
            if (building == null) building = "";
            if (buildingPart == null) buildingPart = "no";

            //--- Some building have no roof defined, consider not a building
            if (building.equals("apartments")) {
                if (tags.get("roof:shape") == null) continue;
            }

            //--- Could be both (which means the outline must also be drawn)
            // System.out.println("Tags:" + tags.get("building"));
            // System.out.println("Tags:" + tags.get("building:part"));
            if (!building.equals("")) outlines.add(w);
            if (!buildingPart.equals("no")) parts.add(w);

        }

        //--- For each building, find the parts
        while (outlines.size() > 0) {
            Way main = outlines.remove(0);

            //--- The list of included parts
            ArrayList<Way> included = new ArrayList<>();

            //--- Create the outling polygone for inclusion detection
            Path2D path = producePath(main);

            //--- Iterate through all parts to find the one included in the
            //--- the main outline building
            for (int j = 0; j < parts.size(); j++) {
                Way pw = parts.get(j);
                if (pw == main) {
                    included.add(pw);
                    continue;
                }
                boolean in = isIncluded(path, pw);
                if (in) included.add(pw);

            }

            if (included.isEmpty()) {
                //--- Produce the one which have no parts
                features.add(produceBuilding(main, new ArrayList<>(), 0, 0));
                continue;
            }

            //--- The key is the part outline
            HashMap<Way, ArrayList<Way>> buildings = new HashMap<>();
            //--- If the parts is processed (as a hole or column)
            ArrayList<Way> columns = new ArrayList<>();
            //--- For each includes part, find the one included in the others
            for (int i = 0; i < included.size(); i++) {
                Way pw = included.get(i);
                // System.out.println("Checking parts inclusion [" + pw.getId() + "]");
                ArrayList<Way> holes = new ArrayList<>();
                buildings.put(pw, holes);

                Map<String, String> tags = pw.getTags();
                double height = 0.0d;
                double minHeight = 0.0d;
                try {
                    if (tags.get("height") != null) height = Double.parseDouble(tags.get("height").replace('m', ' ').replace('\'',' '));
                    if (tags.get("min_height") != null) minHeight = Double.parseDouble(tags.get("min_height").replace('m', ' ').replace('\'',' '));

                } catch (NumberFormatException ex) {
                    ex.printStackTrace();
                }
                //--- Create the outling polygone for inclusion detection
                path = producePath(pw);

                for (int j = 0; j < included.size(); j++) {
                    Way tw = included.get(j);
                    //--- Do not process myself
                    if (tw == pw) continue;

                    List<Long> list = tw.getNodeIds();
                    boolean in = true;
                    for (int k = 0; k < list.size(); k++) {
                        long id = list.get(k);
                        Node n = nodes.get(id);
                        LatLon ll = n.getPosition();
                        if (!path.contains(ll.getLongitude(), ll.getLatitude())) {
                            in = false;
                            break;
                        }
                    }
                    if (in) {
                        // System.out.println("Found inclusion:"+tw.getId());
                        tags = tw.getTags();
                        double pheight = 0;
                        if (tags.get("height") != null) pheight = Double.parseDouble(tags.get("height").replace('m', ' '));
                        // System.out.println("Main part height="+height+", included height="+pheight);
                        if (pheight <= height) {
                            //--- The hole is lower, check if it's lower than the min_height
                            if (pheight <= minHeight) {
                                //--- Not a hole
                                columns.add(tw);

                            } else {
                                //--- Holes which is lower,but add an inner column
                                // System.out.println("[" + tw.getId() + "] is lower than main part, adding hole and column");
                                holes.add(tw);
                                columns.add(tw);
                            }

                        } else if (pheight >= height) {
                            //--- No holes,because it's greater, simply add a column
                            // System.out.println("[" + tw.getId() + "] is greater than main part, adding column only");
                            columns.add(tw);

                        } else {
                            //--- Same height, it's definetly a hole
                            // System.out.println("[" + tw.getId() + "] same height as main part");
                            holes.add(tw);
                        }

                    } else {
                        //--- No in part, go to next part
                        // System.out.println("["+tw.getId()+"] not included");
                    }
                }
            }
            //--- The buildings are now defined in buildings as individual parts
            //--- with holes or not
            Iterator<Way> it = buildings.keySet().iterator();
            while (it.hasNext()) {
                Way p = it.next();
                ArrayList<Way> holes = buildings.get(p);
                //--- Produce the main part (with holes)
                Feature f = produceBuilding(p, holes, 0, 0);
                features.add(f);

            }

            //--- Produce the additional columns
            for (int i = 0; i < columns.size(); i++) {
                features.add(produceBuilding(columns.get(i), new ArrayList<>(), 0, 0));

            }

            //--- Remove the already processed parts
            for (int i = 0; i < included.size(); i++) parts.remove(included.get(i));
            for (int i = 0; i < columns.size(); i++) parts.remove(columns.get(i));

        }

        //--- Produce the parts with no main buildings
        for (int i = 0; i < parts.size(); i++) {
            features.add(produceBuilding(parts.get(i), new ArrayList<>(), 0, 0));
        }
    }

    /**
     * Find if all ids are present is fetched data If true is returned, call
     * fetchMissingObject again (new have arrived which needs to be checked)
     *
     * Filter by bilding related
     */
    private boolean fetchMissingObjects(OsmConnection osm) {
        ArrayList<Long> r2f = new ArrayList<>();
        ArrayList<Long> w2f = new ArrayList<>();
        ArrayList<Long> n2f = new ArrayList<>();
        System.out.println(">>> Fetching missing object");
        boolean needFetching = false;

        //--- Check relations
        Iterator<Relation> rit = relations.values().iterator();
        while (rit.hasNext()) {
            Relation r = rit.next();
            //--- Filter by building related
            Map<String, String> tags = r.getTags();
            if (tags == null) continue;
            
            String type = tags.get("type");
            String building = tags.get("building");
            if (building == null) building = "";
            String buildingPart = tags.get("building:part");
            if (buildingPart == null) buildingPart = "";
            if (type == null) continue;

            boolean process = false;
            //--- If building="yes" or type="building", then process relation
            if (type.equals("building")) process = true;
            if (!building.equals("")) process = true;
            if (!buildingPart.equals("")) process = true;
            if (!process) continue;

            List<RelationMember> list = r.getMembers();
            System.out.println("RELATION [" + r.getId() + "] members => " + list.size());
            for (int i = 0; i < list.size(); i++) {
                RelationMember m = list.get(i);
                if (m.getType() == Type.RELATION) {
                    Relation tmp = relations.get(m.getRef());
                    if (tmp == null) {
                        System.out.println("[" + r.getId() + "] Relation " + m.getRef() + " not found");
                        r2f.add(m.getRef());
                        needFetching = true;
                    }

                } else if (m.getType() == Type.WAY) {
                    Way tmp = ways.get(m.getRef());
                    if (tmp == null) {
                        System.out.println("[" + r.getId() + "] Way " + m.getRef() + " not found");
                        w2f.add(m.getRef());
                        needFetching = true;
                    }

                } else if (m.getType() == Type.NODE) {
                    Node tmp = nodes.get(m.getRef());
                    if (tmp == null) {
                        System.out.println("[" + r.getId() + "] Node " + m.getRef() + " not found");
                        n2f.add(m.getRef());
                        needFetching = true;
                    }

                }
            }
        }

        //--- Check ways
        Iterator<Way> wit = ways.values().iterator();
        while (wit.hasNext()) {
            Way w = wit.next();
            List<Long> list = w.getNodeIds();
            for (int i = 0; i < list.size(); i++) {
                long nodeId = list.get(i);
                Node n = nodes.get(nodeId);
                if (n == null) {
                    n2f.add(nodeId);
                }
            }
        }

        //--- Fetch the relations (batch by 200)
        MapDataDao md = new MapDataDao(osm);
        while (!r2f.isEmpty()) {
            ArrayList<Long> toFetch = new ArrayList<>();
            int cnt = 0;
            while (!r2f.isEmpty()) {
                toFetch.add(r2f.remove(0));
                cnt++;
                if (cnt >= 200) break;
            }
            List<Relation> rel = md.getRelations(toFetch);
            for (int i = 0; i < rel.size(); i++) {
                Relation tmp = rel.get(i);
                relations.put(tmp.getId(), tmp);
            }
        }

        //--- Fetch ways
        while (!w2f.isEmpty()) {
            ArrayList<Long> toFetch = new ArrayList<>();
            int cnt = 0;
            while (!w2f.isEmpty()) {
                toFetch.add(w2f.remove(0));
                cnt++;
                if (cnt >= 200) break;
            }
            List<Way> wa = md.getWays(toFetch);
            for (int i = 0; i < wa.size(); i++) {
                Way tmp = wa.get(i);
                ways.put(tmp.getId(), tmp);
            }
        }
        //--- Fetch nodes
        while (!n2f.isEmpty()) {
            ArrayList<Long> toFetch = new ArrayList<>();
            int cnt = 0;
            while (!n2f.isEmpty()) {
                toFetch.add(n2f.remove(0));
                cnt++;
                if (cnt >= 200) break;
            }
            List<Node> no = md.getNodes(toFetch);
            for (int i = 0; i < no.size(); i++) {
                Node tmp = no.get(i);
                nodes.put(tmp.getId(), tmp);
            }
        }
        System.out.println("<<< Out fetching");
        return needFetching;

    }

    //**************************************************************************
    //*** Run
    //**************************************************************************
    @Override
    public void run() {
        try {
            parseHeaders();
            System.out.println("(D) uri@" + getName() + " " + uri);
            String parts[] = uri.split("/");
            if (parts[1].equals("osmbuildings")) {
                int zoom = Integer.parseInt(parts[2]);
                int x = Integer.parseInt(parts[3]);
                int y = Integer.parseInt(parts[4].split("\\.")[0]);

                //--- Check if in cache
                File f = new File(server.getCachePath(), "" + zoom + File.separator + x + File.separator + y + ".json");
                // System.out.println("CHECKING:"+f.getPath()+" ("+f.exists()+")");
                if (f.exists()) {
                    //--- Check if too old
                    long now = System.currentTimeMillis() / 1000;
                    long last = f.lastModified() / 1000;
                    long diff = now - last;
                    long keepDelay = server.getCacheKeepDelay() * 24 * 60 * 60;
                    if (diff > keepDelay) f.delete();
                }

                if (f.exists()) {
                    InputStream in = new FileInputStream(f);
                    byte[] b = new byte[(int) f.length()];
                    int len = b.length;
                    int total = 0;
                    while (total < len) {
                        int result = in.read(b, total, len - total);
                        if (result == -1) {
                            break;
                        }
                        total += result;
                    }
                    System.out.println("(D) Found file in cache " + f.getPath());
                    sendHttpResponse(HttpURLConnection.HTTP_OK, mimes.get("json"), null, b);

                } else {
                    //--- Produce it
                    osmbuildings(zoom, x, y);
                }

            } else {
                //--- Not found
                sendHttpResponse(HttpURLConnection.HTTP_NOT_FOUND, mimes.get("html"), null, null);
            }

        } catch (Exception ex) {
            ex.printStackTrace();
            sendHttpResponse(HttpURLConnection.HTTP_BAD_REQUEST, mimes.get("html"), null, null);

        }

        //--- Close in any case
        try {
            in.close();
            out.close();
            socket.close();

        } catch (IOException ex) {

        }
    }

    public static void main(String args[]) {
        Gson gson = new GsonBuilder()
                .registerTypeAdapterFactory(new GeometryAdapterFactory())
                .serializeNulls()
                .create();

        JsonPrimitive p = new JsonPrimitive(10);
        String json = gson.toJson(p);
        System.out.println(json);

        json = "{\"type\":\"Point\",\"coordinates\": [23.5,20.125]}";

        Point point = gson.fromJson(json, Point.class);

        HashMap<String, JsonElement> properties = new HashMap<>();
        properties.put("height", p);

        Feature f = Feature.builder()
                .withId("11")
                .withGeometry(point)
                .withProperties(properties)
                .build();

        json = gson.toJson(f);
        System.out.println(json);

        StringWriter w = new StringWriter();
        JsonWriter jw = new JsonWriter(w);
        // jw.setSerializeNulls(true);
        // jw.setLenient(true);
        gson.toJson(p, jw);

        System.out.println("JSON:" + w);

    }
}
