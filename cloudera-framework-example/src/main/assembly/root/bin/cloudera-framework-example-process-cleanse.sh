#!/bin/bash

export ROOT_DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )/..

source $ROOT_DIR/bin/*.env

set -x -e

$ROOT_DIR/bin/cloudera-framework-hadoop.sh "\
  jar $ROOT_DIR/lib/jar/*.jar \
  com.cloudera.framework.example.process.Cleanse \
  -libjars $LIBJARS \
  $ROOT_DIR_HDFS_PARTITIONED_CANONICAL \
  $ROOT_DIR_HDFS_CLEANSED"