/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package org.osmbuildings.tileserver;

/**
 *
 * @author sbodmer
 */
public interface OSMBuildingsTileServerListener {
    public final int STATE_LOG = 0;
    public final int STATE_ERROR = 1;
    public final int STATE_WARNING = 2;
    
    public void buildingsLogs(int state, String line);
    
}
