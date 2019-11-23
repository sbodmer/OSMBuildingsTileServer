# OSMBuildingsTileServer
Serves OpenStreetMap buildings as GeoJSON objects

# Features
This server will get the open street data from main osm server and then produce
json files for buildings (and stored in local file system for later use).

The server uses Tanuki java wrapper to be deployed as a service if needed
https://wrapper.tanukisoftware.com

## Release
Work in progress, but enough for a Preview...

## Building
!!! Only 64bit architecture supported !!!

The project is a Netbeans project, for manual compiling, use the ant build.xml

    cd {cwd}
    ant build

To run the application cd in the newly created dist dir

    cd dist
    bin/osmb.sh console
