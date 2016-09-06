# Elassandra usefull alias

if [ "x$CASSANDRA_HOME" = "x" ]; then
    export CASSANDRA_HOME=$PWD
fi
echo "CASSANDRA_HOME=$CASSANDRA_HOME"

#
# Please set the following variable in your environnement (multihoming support)
export NODE=`hostname`
export NODETOOL_JMX_PORT="7199"

# for debug purpose only
export JVM_DEBUG_PORT="4242"
export JVM_DEBUG_WAIT="n"

export CASSANDRA_CONF=$CASSANDRA_HOME/conf
export CASSANDRA_DATA=$CASSANDRA_HOME/data
export CASSANDRA_LOGS=$CASSANDRA_HOME/logs

# Kill cassandra process
function ckill() {
   kill `ps ax | grep java | grep $CASSANDRA_HOME | awk '{ print $1}'`
}

function cstatus() {
   ps ax | grep java | grep $CASSANDRA_HOME
}

function cleanall() {
   rm -rf $CASSANDRA_DATA/*
   rm -rf $CASSANDRA_LOGS/*
}

function cleanlogs() {
	rm -rf $CASSANDRA_HOME/logs/*.log*
}

function get() {
   curl -XGET "http://$NODE:9200/$1" $2 $2 $4 $5
}
function put() {
   curl -XPUT "http://$NODE:9200/$1" $2 $2 $4 $5
}
function post() {
   curl -XPOST "http://$NODE:9200/$1" $2 $2 $4 $5
}
function delete() {
   curl -XDELETE "http://$NODE:9200/$1" $2 $2 $4 $5
}

function close() {
   curl -XPOST "http://$NODE:9200/$1/_close"
}

function open() {
   curl -XPOST "http://$NODE:9200/$1/_open"
}

function forcemerge() {
   curl -XPOST "http://$NODE:9200/$1/_forcemerge"
}

function clearcache() {
   curl -XPOST "http://$NODE:9200/$1/_cache/clear"
}

# Cassandra aliases
alias cstart='$CASSANDRA_HOME/bin/cassandra'
alias cdebug='$CASSANDRA_HOME/bin/cassandra -d'
alias cstop='$CASSANDRA_HOME/bin/nodetool -p $NODETOOL_JMX_PORT -h $NODE stopdaemon'
alias cqlsh='$CASSANDRA_HOME/bin/cqlsh $NODE'
alias nodetool='$CASSANDRA_HOME/bin/nodetool -p $NODETOOL_JMX_PORT'

alias clog='less $CASSANDRA_LOGS/system.log'

# Start Elassandra
alias ecstart='$CASSANDRA_HOME/bin/cassandra -e'
alias ecdebug='$CASSANDRA_HOME/bin/cassandra -d -e'

# Elasticsearch aliases
alias health='curl -XGET http://$NODE:9200/_cluster/health/?pretty=true'
alias state='curl -XGET http://$NODE:9200/_cluster/state/?pretty=true'
alias status='curl -XGET http://$NODE:9200/_status/?pretty=true'
alias stats='curl -XGET http://$NODE:9200/_stats?pretty=true'
alias shards='curl -s -XGET http://$NODE:9200/_cat/shards?v | sort'
alias indices='curl -s -XGET http://$NODE:9200/_cat/indices?v | sort'
alias fielddata='curl -XGET http://$NODE:9200/_cat/fielddata/body,text?v'
alias thread_pool='curl -XGET http://$NODE:9200/_cat/thread_pool?v'
alias pending_tasks='curl -XGET http://$NODE:9200/_cat/pending_tasks?v'
alias segments='curl -XGET http://$NODE:9200/_cat/segments?v'
alias allocation='curl -XGET http://$NODE:9200/_cat/allocation?v'
alias nodes='curl -XGET http://$NODE:9200/_cat/nodes?h=id,ip,heapPercent,ramPercent,fileDescriptorPercent,segmentsCount,segmentsMemory'
alias settings='curl -XGET http://$NODE:9200/_cluster/settings?pretty=true'

alias open='open'
alias close='close'
alias forcemerge='forcemerge'
alias clearcache='clearcache'

alias get='get'
alias put='put'
alias post='post'
alias delete='delete'
