#!/bin/sh
#
# Copyright 2014 Coinport Inc. All Rights Reserved.
# Author: c@coinport.com (Chao Ma)
thrift --gen js:node -o ./ bitway-client/src/main/thrift/data.thrift
thrift --gen js:node -o ./ bitway-client/src/main/thrift/message.thrift

if [ ! -d 'log' ]; then
    mkdir log
fi

node src/coinport/bitway/index.js
