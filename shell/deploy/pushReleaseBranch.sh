#!/bin/sh
#
# Copyright 2014 Coinport Inc. All Rights Reserved.
# Author: xiaolu@coinport.com (Xiaolu)

branch=$1
if [ -z "$branch" ];then
  branch="rc_"`date +%Y%m%d`
fi

git checkout master
git fetch
git rebase origin/master
git checkout -b $branch origin/master
git push origin $branch
