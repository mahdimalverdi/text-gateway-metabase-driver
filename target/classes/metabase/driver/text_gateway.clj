(ns metabase.driver.text-gateway
  (:require [metabase.driver :as driver])
  (:import [com.example.textgateway ApiClient]))

(driver/register! :text-gateway)

(defmethod driver/can-connect? :text-gateway [_ details]
  (ApiClient/ping (:base_url details)))

(defmethod driver/execute-query :text-gateway [_ query context respond]
  (let [result (ApiClient/runQuery (:base_url query)
                                   (str (:native query)))]
    (respond {:columns (ApiClient/columns result)
              :rows (ApiClient/rows result)})))
