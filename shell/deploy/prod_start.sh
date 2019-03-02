
#
# Copyright 2014 bitway Inc. All Rights Reserved.
# Author: c@bitway.com (Chao Ma)

# =========================== The parameters for JVM ===========================
MaxHeapSizeM=4096  # The max heap size
MaxPermSizeM=512  # The permanent size
CMSRatio=70
InitHeapSizeRatio=4  # the max heap size / the init heap size
NewRatioA=3  # all heap space size / new heap space size
XmsSizeM=`expr $MaxHeapSizeM / $InitHeapSizeRatio`
NewSizeM=`expr $XmsSizeM / $NewRatioA`
MaxNewSizeM=`expr $MaxHeapSizeM / $NewRatioA`
NumOfFullGCBeforeCompaction=1
maillist=chunming@bitway.com,c@bitway.com,d@bitway.com

Xms="-Xms${XmsSizeM}m"  # The init heap size
Xmx="-Xmx${MaxHeapSizeM}m"

NewSize="-XX:NewSize=${NewSizeM}m"  # The init size of new heap space
MaxNewSize="-XX:MaxNewSize=${MaxNewSizeM}m"  # The max size of new heap space

PermSize="-XX:PermSize=${MaxPermSizeM}m"
MaxPermSize="-XX:MaxPermSize=${MaxPermSizeM}m"

# If full GC use CMS, this is the default new GC. Also explicit lists here
UseParNewGC="-XX:+UseParNewGC"
UseConcMarkSweepGc="-XX:+UseConcMarkSweepGC"  # Use CMS as full GC
CMSInitOccupancyFraction="-XX:CMSInitiatingOccupancyFraction=${CMSRatio}"
CMSFullGCsBeforeCompaction="-XX:CMSFullGCsBeforeCompaction=${NumOfFullGCBeforeCompaction}"

# GCLog="-Xloggc:./gc.log"
# GCStopTime="-XX:+PrintGCApplicationStoppedTime"
# GCTimeStamps="-XX:+PrintGCTimeStamps"
# GCDetails="-verbose:gc -XX:+PrintGCDetails -XX:+PrintGCDateStamps"
# ======================== End the parameters for JVM ==========================



################################################################################
#scprit start
################################################################################
cd /var/bitway/backend

# get jar versionid ---------------------------------------------------
version=`grep "val bitwayVersion"  /var/bitway/code/bitway/project/Build.scala | cut -d '"' -f2`

COMMAND="java -server $Xms $Xmx $NewSize $MaxNewSize $PermSize $MaxPermSize $UseParNewGC $UseConcMarkSweepGc $CMSInitOccupancyFraction $GCLog $GCStopTime $GCTimeStamps $GCDetails $CMSFullGCsBeforeCompaction -cp /var/bitway/backend/bitway-backend-assembly-$version.jar -Dconfig.resource=application-prod.conf com.coinport.bitway.Main 3551 172.31.20.24:3551 all 172.31.20.24"
#COMMAND="java -server $Xms $Xmx $NewSize $MaxNewSize $PermSize $MaxPermSize $UseParNewGC $UseConcMarkSweepGc $CMSInitOccupancyFraction $GCLog $GCStopTime $GCTimeStamps $GCDetails $CMSFullGCsBeforeCompaction -cp ./coinex-backend-assembly-1.1.18-SNAPSHOT.jar com.bitway.coinex.CoinexApp 25551 127.0.0.1:25551 all 127.0.0.1"

echo $COMMAND
if [ -f "./nohup.out" ]; then
  rm nohup.out
fi
nohup $COMMAND &

