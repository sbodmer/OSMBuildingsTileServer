#!/bin/bash
PID=$(<Temp/knopd.pid)
echo "Dumping jvm memory to Heap.hprof for pid : $PID"
jmap -dump:live,format=b,file=Heap.hprof $PID
echo "Finished"

