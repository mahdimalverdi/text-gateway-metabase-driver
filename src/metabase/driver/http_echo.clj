(ns metabase.driver.http-echo
  "A lightweight Metabase driver that sends the submitted query text to an HTTP API
   (GET or POST) and returns the JSON response as a table."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [metabase.driver :as driver]
            [metabase.util.log :as log]))

;; -------------------------------
;; Utilities
;; -------------------------------

(def ^:private query-param-name "q")

(def ^:private error-column
  {:name "error"
   :display_name "Error"
   :base_type :type/Text
   :effective_type :type/Text
   :semantic_type :type/Text
   :database_type "text"
   :description "Error message from the driver."
   :field_ref [:field-literal "error" {:base-type :type/Text}]
   :visibility_type :normal
   :source :native})

(def ^:private error-metadata {:columns [error-column] :cols [error-column]})

(defn- respond-error [respond msg]
  (respond error-metadata [[msg]]))

(defn- key->str [k]
  (cond (keyword? k) (name k)
        (string? k) k
        :else (str k)))

(defn- display-name [k]
  (-> (key->str k)
      (str/replace #"[._\[\]-]+" " ")
      (str/capitalize)))

(defn- fmt-value [v]
  (cond
    (or (string? v) (number? v) (boolean? v)) (str v)
    (map? v) (json/generate-string v)
    (sequential? v) (json/generate-string v)
    :else (str v)))

(defn- flatten-json [value]
  (letfn [(step [path v acc]
            (cond
              (map? v) (reduce-kv (fn [a k val] (step (conj path k) val a)) acc v)
              (sequential? v) (reduce (fn [a [idx val]] (step (conj path idx) val a))
                                      acc (map-indexed vector v))
              :else (assoc acc path v)))]
    (let [res (step [] value {})]
      (if (seq res) res {[] value}))))

(defn- path->col-name [path]
  (if (seq path)
    (reduce (fn [acc s]
              (let [seg (cond
                          (keyword? s) (name s)
                          (int? s) (str "[" s "]")
                          :else (str s))]
                (if (str/starts-with? seg "[")
                  (str acc seg)
                  (if (empty? acc) seg (str acc "." seg)))))
            "" path)
    "result"))

(defn- ->columns-and-rows [body]
  (let [rows (if (sequential? body) body [body])]
    (if (seq rows)
      (let [flat-rows (map #(into {} (map (fn [[p v]] [(path->col-name p) v])
                                          (flatten-json %)))
                           rows)
            cols (->> (apply merge flat-rows)
                      keys
                      sort
                      (mapv (fn [k]
                              {:name k
                               :display_name (display-name k)
                               :base_type :type/Text
                               :database_type "text"})))]
        {:columns cols
         :cols cols
         :rows (mapv (fn [r] (mapv #(fmt-value (get r (:name %))) cols)) flat-rows)})
      {:columns [] :cols [] :rows []})))

;; -------------------------------
;; Connection handling
;; -------------------------------

(defmethod driver/connection-properties :http-echo
  [_]
  [{:name "endpoint"
    :display-name "API Endpoint"
    :placeholder "https://example.com/api"
    :required true
    :section "connection"
    :description "Base URL of the HTTP API to send queries to."}
   {:name "method"
    :display-name "HTTP Method"
    :placeholder "GET"
    :default "GET"
    :options ["GET" "POST"]
    :section "connection"
    :description "HTTP method to use (GET or POST)."}])

(defmethod driver/can-connect? :http-echo
  [_ details]
  (boolean (:endpoint details)))

;; -------------------------------
;; Query Execution
;; -------------------------------

(defmethod driver/execute-reducible-query :http-echo
  [_ query context respond]
  (let [query-text (some-> query :native :query str/trim)
        details (-> context :database :details)
        endpoint (:endpoint details)
        method (-> (get details :method "GET") str/lower-case)]
    (cond
      (not endpoint)
      (do (log/warn "HTTP Echo: no endpoint configured." {:details details})
          (respond-error respond "Missing endpoint in connection details."))

      (not (seq query-text))
      (respond-error respond "No query text provided.")

      :else
      (try
        (let [opts {:accept :json :as :json :throw-exceptions false}
              resp (case method
                     "post" (http/post endpoint (assoc opts :form-params {query-param-name query-text}))
                     (http/get  endpoint (assoc opts :query-params {query-param-name query-text})))
              {:keys [status body]} resp]
          (if (<= 200 status 299)
            (let [{:keys [columns rows]} (->columns-and-rows body)]
              (respond {:columns columns :cols columns} rows))
            (respond-error respond (format "HTTP %s calling %s" status endpoint))))
        (catch Exception e
          (log/error e "HTTP Echo driver failed request")
          (respond-error respond (.getMessage e)))))))

;; -------------------------------
;; Driver registration
;; -------------------------------

(defonce ^:private register-driver-once
  (delay
    (try
      (driver/register! :http-echo)
      (catch IllegalArgumentException e
        (if (re-find #"already registered" (.getMessage e))
          (log/info "HTTP Echo driver already registered; continuing.")
          (throw e))))))

(defn init! [] (force register-driver-once))
(force register-driver-once)
