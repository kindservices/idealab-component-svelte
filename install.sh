#!/usr/bin/env bash

thisDir=$(cd `dirname $0` && pwd)
webDir="$thisDir/web"


pushd "$thisDir/kafka"
echo "============== installing kafka in `pwd` =============="
make installArgo
popd


pushd "$thisDir/pinotdb"
echo "============== installing pinot in `pwd` =============="
make installArgo
popd


pushd "$thisDir/pinot-bff"
echo "============== installing pinot-bff in `pwd` =============="
make installArgo
popd

pushd "$webDir"
echo "============== installing web in `pwd` =============="
make installArgo
popd
