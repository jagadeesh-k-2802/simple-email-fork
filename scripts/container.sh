#!/bin/sh

guix shell \
     -m manifest.scm \
     --container -F -N -P \
     --share=/opt/android-sdk \
     --share=$HOME/.android \
     $@
