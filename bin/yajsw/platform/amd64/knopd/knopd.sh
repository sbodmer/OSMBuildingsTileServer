#!/bin/sh
### BEGIN INIT INFO
# Provides: Knop4
# Required-Start: 
# Required-Stop: 
# Default-Start:  2 3 4 5 
# Default-Stop: 0 1 6 
# Short-Description: yajsw wrapper for Knop4
# Description: Knop4
### END INIT INFO
# config: conf/knopd.conf
# pidfile: Temp/wrapper.Knop4.pid
# apppidfile: Temp/wrapper.java.Knop4.pid
#-----------------------------------------------------------------------------
# YAJSW sh script template.  Suitable for starting and stopping
#  wrapped daemon on POSIX platforms.
#-----------------------------------------------------------------------------

status() {
     java -Dwrapper.pidfile=Temp/wrapper.Knop4.pid -Dwrapper.service=true -Dwrapper.visible=false -Djna_tmpdir=Temp -jar wrapper.jar -q conf/knopd.conf
}

stopit() {
    echo "Stopping Knop4 ..."
	 java -Dwrapper.pidfile=Temp/wrapper.Knop4.pid -Dwrapper.service=true -Dwrapper.visible=false -Djna_tmpdir=Temp -jar wrapper.jar -px conf/knopd.conf
}
startit() {
    echo "Starting Knop4 ..."
     java -Dwrapper.pidfile=Temp/wrapper.Knop4.pid -Dwrapper.service=true -Dwrapper.visible=false -Djna_tmpdir=Temp -jar wrapper.jar -tx conf/knopd.conf
}
consoleit() {
    echo "Staring Knop4 in console mode (ctrl-c to break) ..."
     java -Dwrapper.pidfile=Temp/wrapper.Knop4.pid -Dwrapper.visible=true -Djna_tmpdir=Temp -jar wrapper.jar -c conf/knopd.conf
}

case "$1" in
    'start')
        startit
        ;;

    'stop')
        stopit
        ;;

    'restart')
        stopit
        startit
        ;;
        
        
    'force-reload')
        stopit
        startit
        ;;

    'status')
        status
        ;;

    'console')
        consoleit
        ;;

    *)
        echo "Usage: $0 { start | stop | restart | status | console }"
        exit 1
        ;;
esac

exit $?
