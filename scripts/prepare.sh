#!/bin/sh

FILE="app/build.gradle"
VERSIONCODE=`grep "versionCode .*" $FILE | sed "s/versionCode //"`
sed -i "s/versionCode .*$/versionCode $(($VERSIONCODE + 1))/; s/versionName .*$/versionName \"$1\"/" $FILE
