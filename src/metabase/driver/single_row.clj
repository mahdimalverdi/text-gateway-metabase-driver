(ns metabase.driver.single-row
  "A minimal Metabase driver that always returns a single static row."
  (:require [metabase.driver :as driver]))

(driver/register! :single-row)

(def ^:private column-metadata
  [{:name "message"
    :display_name "Message"
    :base_type :type/Text
    :effective_type :type/Text
    :database_type "text"
    :description "Static message returned by the single-row driver."}])

(def ^:private static-row
  [["Hello from the single-row driver!"]])

(defmethod driver/can-connect? :single-row
  [_ _details]
  true)

(defmethod driver/execute-query :single-row
  [_ query _context respond]
  ;; Ignore the incoming query and return a single static row.
  (respond {:columns column-metadata
            :rows static-row}))
