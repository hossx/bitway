#!/bin/sh
#
# Copyright 2014 coinport Inc. All Rights Reserved.
# Author: xiaolu@coinport (Wu Xiaolu)

day=`date +%Y%m%d`

count=`ls -l /var/bitway/backup | grep $day | wc -l`
if [ "$count" -eq "0" ];then
  dirname=$day
else
  dirname=$day"-"$count
fi
mkdir -p /var/bitway/backup/backend/$dirname
echo "create backup folder : "$dirname
cp /var/bitway/backend/bitway-backend-assembly-* /var/bitway/backup/backend/$dirname
