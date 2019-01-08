(ns jackdaw.test.serde
  (:require
   [clojure.tools.logging :as log]
   [jackdaw.serdes.avro :as avro-serde]
   [jackdaw.serdes.edn :as edn-serde]
   [jackdaw.serdes.json :as json-serde])
  (:import
   (org.apache.kafka.clients.consumer ConsumerRecord)
   (org.apache.kafka.common.serialization Deserializer Serdes Serializer
                                          ByteArraySerializer
                                          ByteArrayDeserializer)
   (org.apache.kafka.common.errors SerializationException)))


;; Access to various serdes

(defn local-schema-loader []
  (fn [topic-config key?]
    (if key?
      "{\"type\":\"string\"}"
      (when-let [r (.getResource
                     (.getContextClassLoader (Thread/currentThread))
                     (str (:topic.metadata/value-schema-name topic-config) ".json"))]
        (slurp r)))))


(defn create-avro-serde [schema-registry-config schema-loader topic-config key?]
  (avro-serde/serde
    ;; Registry of avro <-> clj types
    avro-serde/+base-schema-type-registry+
    schema-registry-config
    ;; Avro serdes config
    {:key? key?
     :avro/schema (schema-loader topic-config key?)}))

(defn create-serdes-lookup [schema-registry-config schema-loader]
  {:avro-key (fn [topic-config]
               (create-avro-serde
                 schema-registry-config schema-loader topic-config true))
   :avro-value (fn [topic-config]
                 (create-avro-serde
                   schema-registry-config schema-loader topic-config false))
   :edn (fn [_]
          (edn-serde/serde))
   :json (fn [_]
           (json-serde/serde))
   :long (fn [_]
           (Serdes/Long))
   :string (fn [_]
             (Serdes/String))})

(defn serdes-resolver [schema-registry-config schema-loader]
  (fn [topic-config]
    (letfn [(get-serde [k]
              (let [f (get
                        (create-serdes-lookup schema-registry-config schema-loader)
                        (get topic-config k))]
                (f topic-config)))]
      (merge
        topic-config
        {:key-serde (get-serde :key-serde)
         :value-serde (get-serde :value-serde)}))))

(defn local-serdes-resolver [schema-registry-config]
  (serdes-resolver schema-registry-config (local-schema-loader)))


;; Serialization/Deserialization
;;
;; Using a byte-array-serde allows us to use a single consumer to consume
;; from all topics. The test-machine knows how to further deserialize
;; the topic-info based on the topic-config supplied by the test author.

(def byte-array-serde
  "Byte-array key and value serde."
   {:key-serde (Serdes/ByteArray)
    :value-serde (Serdes/ByteArray)})

(def byte-array-serializer (ByteArraySerializer.))
(def byte-array-deserializer (ByteArrayDeserializer.))

(defn serialize-key
  "Serializes a key."
  [k {topic-name :topic-name
      key-serde :key-serde :as t}]
  (when k
    (-> (.serializer key-serde)
        (.serialize topic-name k))))

(defn serialize-value
  [v {topic-name :topic-name
      value-serde :value-serde :as t}]
  (when v
    (-> (.serializer value-serde)
        (.serialize topic-name v))))

(defn serializer
  "Serializes a message."
  [topic]
  (fn [record]
    (assoc record
           :key (serialize-key (:key record) topic)
           :value (serialize-value (:value record) topic))))

(defn deserialize-key
  "Deserializes a key."
  [k {topic-name :topic-name
      key-serde :key-serde}]
  (when k
    (-> (.deserializer key-serde)
        (.deserialize topic-name k))))

(defn deserialize-value
  "Deserializes a value."
  [v {topic-name :topic-name
      value-serde :value-serde}]
  (when v
    (-> (.deserializer value-serde)
        (.deserialize topic-name v))))

(defn deserializer
  "Deserializes a message."
  [topic]
  (fn [m]
    {:topic (:topic-name topic)
     :key (deserialize-key (:key m) topic)
     :value (deserialize-value (:value m) topic)
     :partition (:partition m 0)
     :offset (:offset m 0)}))

(defn deserializers
  "Returns a map of topics to the corresponding deserializer"
  [topic-config]
  (->> topic-config
       (map (fn [[k v]]
              [(:topic-name v)
               (deserializer v)]))
       (into {})))

(defn serializers
  "Returns a map of topic to the corresponding serializer"
  [topic-config]
  (->> topic-config
       (map (fn [[k v]]
              [(:topic-name v)
               (serializer v)]))
       (into {})))

(defn serde-map
  [topic-config]
  {:serializers (serializers topic-config)
   :deserializers (deserializers topic-config)})

(defn apply-serializers
  [serializers m]
  (let [topic (:topic m)
        serialize (get serializers (:topic-name topic))]
    (if (nil? serialize)
      (throw (IllegalArgumentException.
              (str "Message refers to unknown topic: " (:topic-name topic))))
      (serialize m))))

(defn apply-deserializers
  [deserializers m]
  (let [topic-name (:topic m)
        deserialize (get deserializers topic-name)]
    (if (nil? deserialize)
      (throw (IllegalArgumentException.
              (str "Record comes from unknown topic: " topic-name)))
      (deserialize m))))