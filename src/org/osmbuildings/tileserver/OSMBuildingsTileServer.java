/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.osmbuildings.tileserver;

import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 *
 * @author sbodmer
 */
public class OSMBuildingsTileServer extends Thread {

    String ip = "0.0.0.0";
    int port = 8088;
    File cache = null;
    String provider = "https://api.openstreetmap.org/api/0.6/";
    // String provider = "https://master.apis.dev.openstreetmap.org/api/0.6/";
    
    public OSMBuildingsTileServer(String ip, int port, String c) {
        super("OSMBuildingsTileServer");
        this.ip = ip;
        this.port = port;
       
        cache = new File(c,"OSMBuildings");
        cache.mkdirs();
        
        System.out.println("(cache) "+cache.getPath());
    }

    //**************************************************************************
    //*** API
    //**************************************************************************
    public File getCachePath() {
        return cache;
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

                    System.out.println(">>> OSMBuildingsTileServer listening on " + ssocket.getInetAddress() + ":" + ssocket.getLocalPort());
                    int iteration = 0;
                    while (isInterrupted() == false) {
                        try {
                            Socket socket = ssocket.accept();
                            System.out.println("(I) Received connection from "+socket.getInetAddress().getHostAddress());
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
                    System.out.println("<<< OSMBuildingsTileServer stopped");
                    
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
        int port = 8088;
        String host = "0.0.0.0";
        String cache = "Cache";
        
        for (int i=0;i<args.length;i++) {
            String a = args[i];
            if (a.equals("-host")) {
                host = args[i+1];
                i++;
            } else if (a.equals("-port")) {
                port = Integer.parseInt(args[i+1]);
                i++;
                
            } else if (a.equals("-help")) {
                System.out.println("OSMBuildingsTileServer");
                System.out.println("Copyright 2018 by Stephan Bodmer");
                System.out.println("");
                System.out.println("-host {host}");
                System.out.println(" To server ip address to listen on");
                System.out.println("");
                System.out.println("-port {port}");
                System.out.println(" The port of the server");
                System.out.println("");
                System.out.println("-cache {cache folder}");
                System.out.println(" The folder where to store the resolved .json files");
                System.exit(0);
            }
        }
        
        OSMBuildingsTileServer server = new OSMBuildingsTileServer(host, port, cache);
        server.start();
    }

}
