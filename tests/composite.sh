cqlsh <<EOF
CREATE KEYSPACE IF NOT EXISTS composite WITH replication={ 'class':'NetworkTopologyStrategy', 'DC1':'1' };
CREATE TABLE IF NOT EXISTS composite.t1 ( 
a text,
b text,
c bigint,
primary key ((a),b)
);
insert into composite.t1 (a,b,c) VALUES ('a','b1',1);
insert into composite.t1 (a,b,c) VALUES ('b','b1',1);

CREATE TABLE IF NOT EXISTS composite.t2 ( 
a text,
b text,
c bigint,
d bigint,
primary key ((a),b,c)
);
insert into composite.t2 (a,b,c,d) VALUES ('a','b2',2,1);
insert into composite.t2 (a,b,c,d) VALUES ('a','b2',3,1);


CREATE TABLE IF NOT EXISTS composite.t3 ( 
a text,
b text,
c bigint,
d bigint,
primary key ((a,b),c)
);
insert into composite.t3 (a,b,c,d) VALUES ('a','b3',2,3);
insert into composite.t3 (a,b,c,d) VALUES ('a','b3',3,3);
EOF


curl -XPUT "http://$NODE:9200/composite/" -d '{ "settings" : { "number_of_replicas" : 0 } }'



curl -XPUT "http://$NODE:9200/composite/_mapping/t1" -d '{ "t1" : { "columns_regexp" : ".*" }}'
curl -XPUT "http://$NODE:9200/composite/_mapping/t2" -d '{ "t2" : { "columns_regexp" : ".*" }}'
curl -XPUT "http://$NODE:9200/composite/_mapping/t3" -d '{ "t3" : { "columns_regexp" : ".*" }}'

curl -XGET "http://$NODE:9200/composite/t1/\[\"a\",\"b1\"\]" 
curl -XGET "http://$NODE:9200/composite/t2/\[\"a\",\"b2\",2\]" 
curl -XGET "http://$NODE:9200/composite/t3/\[\"a\",\"b3\",2\]" 


curl -XGET "http://$NODE:9200/composite/t1/_search?pretty=true&q=c:1"
curl -XGET "http://$NODE:9200/composite/t2/_search?pretty=true&q=d:1"
curl -XGET "http://$NODE:9200/composite/t3/_search?pretty=true&q=d:3"

curl "$NODE:9200/composite/t1/_mget?pretty=true" -d '{
    "docs" : [
        { "_id" : "[\"a\",\"b1\",1]" },
        { "_id" : "[\"b\",\"b1\",1]" }
    ]
}'
curl "$NODE:9200/composite/t2/_mget?pretty=true" -d '{
    "docs" : [
        { "_id" : "[\"a\",\"b2\",2]" },
        { "_id" : "[\"a\",\"b2\",3]" }
    ]
}'
curl "$NODE:9200/composite/t3/_mget?pretty=true" -d '{
    "docs" : [
        { "_id" : "[\"a\",\"b3\",2]" },
        { "_id" : "[\"a\",\"b3\",3]" }
    ]
}'


curl -XDELETE "http://$NODE:9200/composite"