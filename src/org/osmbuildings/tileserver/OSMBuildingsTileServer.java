/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.osmbuildings.tileserver;

import java.io.File;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Properties;

/**
 *
 * @author sbodmer
 */
public class OSMBuildingsTileServer extends Thread {

    Properties p = null;
    String ip = "0.0.0.0";
    int port = 8088;
    int cacheDelay = 30;    //--- In days
    File cache = null;
    String provider = "https://api.openstreetmap.org/api/0.6/";
    // String provider = "https://master.apis.dev.openstreetmap.org/api/0.6/";

    public OSMBuildingsTileServer(Properties p) {
        super("OSMBuildingsTileServer");
        this.p = p;
        ip = p.getProperty("HOST", "0.0.0.0");

        cache = new File(p.getProperty("CACHE_FOLDER"));
        cache.mkdirs();
        System.out.println("(I) Cache path is " + cache.getPath());
        try {
            port = Integer.parseInt(p.getProperty("PORT"));
            cacheDelay = Integer.parseInt(p.getProperty("CACHE_KEEP_DELAY"));
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        Thread main = this;
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                main.interrupt();
                //--- Wait 10s for the main process to quit
                int cnt = 0;
                try {
                    while (main.isAlive()) {
                        Thread.sleep(1000);
                        cnt++;
                        if (cnt > 0) break;
                    }
                    
                } catch (InterruptedException ex) {

                }
            }
        });
    }

    //**************************************************************************
    //*** API
    //**************************************************************************
    public File getCachePath() {
        return cache;
    }

    public int getCacheKeepDelay() {
        return cacheDelay;
    }
    
    public String getApiProvider() {
        return provider;
    }

    //**************************************************************************
    //*** Run
    //***************************************************************************
    @Override
    public void run() {
        ThreadGroup tg = new ThreadGroup("ClientConnections");
        try {
            while (isInterrupted() == false) {
                try {
                    //--- Listen only on localhost
                    ServerSocket ssocket = null;
                    if (ip.equals("0.0.0.0")) {
                        InetAddress inet = InetAddress.getByName(ip);
                        ssocket = new ServerSocket(port, 10, inet);

                    } else {
                        ssocket = new ServerSocket(port, 10);
                    }

                    ssocket.setSoTimeout(1000);

                    System.out.println("(M) OSMBuildingsTileServer listening on " + ssocket.getInetAddress() + ":" + ssocket.getLocalPort());
                    int iteration = 0;
                    while (isInterrupted() == false) {
                        try {
                            Socket socket = ssocket.accept();
                            System.out.println("(I) Received connection from " + socket.getInetAddress().getHostAddress());
                            OSMBuildingsHttpConnection con = new OSMBuildingsHttpConnection(tg, socket, this);
                            con.start();

                        } catch (SocketTimeoutException ex) {
                            //--- Nothing here, wait next iteration

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }

                        iteration++;
                        if ((iteration % 60) == 0) {
                            houseKeeping();

                        }
                    }

                    ssocket.close();
                    System.out.println("(M) OSMBuildingsTileServer stopped");

                } catch (Exception ex) {
                    ex.printStackTrace();

                }
                Thread.sleep(10000);
            }

        } catch (InterruptedException ex) {
            //---
        }
        tg.interrupt();
        // System.out.println("<<< KnopResources Httpd is dead");
    }

    //**************************************************************************
    //*** Private
    //**************************************************************************
    private void houseKeeping() {

    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        String port = "";
        String host = "";
        String cache = "";
        String config = "";

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-host")) {
                host = args[i + 1];
                i++;
            } else if (a.equals("-port")) {
                port = args[i + 1];
                i++;

            } else if (a.equals("-cache")) {
                cache = args[i + 1];
                i++;

            } else if (a.equals("-config")) {
                config = args[i + 1];
                i++;

            } else if (a.equals("-help")) {
                System.out.println("OSMBuildingsTileServer");
                System.out.println("Copyright 2019 by Stephan Bodmer");
                System.out.println("");
                System.out.println("-host {host}");
                System.out.println(" To server ip address to listen on");
                System.out.println("");
                System.out.println("-port {port}");
                System.out.println(" The port of the server");
                System.out.println("");
                System.out.println("-cache {cache folder}");
                System.out.println(" The folder where to store the resolved .json files");
                System.out.println("");
                System.out.println("-config {config properties}");
                System.out.println(" The main config file");
                System.exit(0);
            }
        }

        Properties p = new Properties();
        p.setProperty("HOST", "0.0.0.0");
        p.setProperty("PORT", "8088");
        p.setProperty("CACHE_FOLDER", System.getProperty("user.dir") + File.separator + "Cache");
        p.setProperty("CACHE_KEEP_DELAY", "30");
        try {
            if (!config.equals("")) {
                p.load(new FileReader(config));
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }
        if (!cache.equals("")) p.setProperty("CACHE_FOLDER", cache);
        if (!host.equals("")) p.setProperty("HOST", host);
        if (!port.equals("")) p.setProperty("PORT", port);
        OSMBuildingsTileServer server = new OSMBuildingsTileServer(p);
        server.start();
    }

}
