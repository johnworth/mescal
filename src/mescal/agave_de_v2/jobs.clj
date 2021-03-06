(ns mescal.agave-de-v2.jobs
  (:use [clojure.java.io :only [file]]
        [medley.core :only [remove-vals]])
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.string :as string]
            [mescal.agave-de-v2.app-listings :as app-listings]
            [mescal.agave-de-v2.job-params :as params]
            [mescal.agave-de-v2.constants :as c]
            [mescal.util :as util]))

(def ^:private timestamp-formatter
  (tf/formatter "yyyy-MM-dd-HH-mm-ss.S"))

(defn- add-param-prefix
  [prefix param]
  (if-not (string/blank? (str prefix))
    (keyword (str prefix "_" (name param)))
    param))

(defn- params-for
  ([config param-prefix app-section]
     (params-for config param-prefix app-section identity))
  ([config param-prefix app-section preprocessing-fn]
     (let [get-param-val (comp preprocessing-fn config (partial add-param-prefix param-prefix))]
       (->> (map (comp keyword :id) app-section)
            (map (juxt identity get-param-val))
            (into {})
            (remove-vals nil?)))))

(defn- prepare-params
  [agave app param-prefix config]
  {:inputs     (params-for config param-prefix (app :inputs) #(.agaveUrl agave %))
   :parameters (params-for config param-prefix (app :parameters) #(if (map? %) (:value %) %))})

(def ^:private submitted "Submitted")
(def ^:private running "Running")
(def ^:private failed "Failed")
(def ^:private completed "Completed")

(def ^:private job-status-translations
  {"PENDING"            submitted
   "STAGING_INPUTS"     submitted
   "CLEANING_UP"        running
   "ARCHIVING"          running
   "STAGING_JOB"        submitted
   "FINISHED"           completed
   "KILLED"             failed
   "FAILED"             failed
   "STOPPED"            failed
   "RUNNING"            running
   "PAUSED"             running
   "QUEUED"             submitted
   "SUBMITTING"         submitted
   "STAGED"             submitted
   "PROCESSING_INPUTS"  submitted
   "ARCHIVING_FINISHED" completed
   "ARCHIVING_FAILED"   failed})

(defn- job-notifications
  [callback-url]
  [{:url        callback-url
    :event      "*"
    :persistent true}])

(defn- build-job-name
  [submission]
  (format "%s_%04d" (:job_id submission) (:step_number submission 1)))

(defn prepare-submission
  [agave app submission]
  (->> (assoc (prepare-params agave app (:paramPrefix submission) (:config submission))
         :name           (build-job-name submission)
         :appId          (:app_id submission)
         :appName        (app-listings/get-app-name app)
         :appDescription (:shortDescription app "")
         :archive        true
         :archivePath    (.agaveFilePath agave (:output_dir submission))
         :archiveSystem  (.storageSystem agave)
         :notifications  (job-notifications (:callbackUrl submission)))
       (remove-vals nil?)))

(defn- app-enabled?
  [statuses jobs-enabled? listing]
  (and jobs-enabled?
       (:available listing)
       (= "up" (statuses (:executionHost listing)))))

(defn- get-result-folder-id
  [agave job]
  (when-let [agave-path (or (:archivePath job) (get-in job [:_links :archiveData :href]))]
    (.irodsFilePath agave agave-path)))

(defn format-job*
  [agave app-id app-name app-description job]
  {:id              (str (:id job))
   :app_id          app-id
   :app_description app-description
   :app_name        app-name
   :description     ""
   :enddate         (or (util/to-utc (:endTime job)) "")
   :system_id       c/hpc-system-id
   :name            (:name job)
   :raw_status      (:status job)
   :resultfolderid  (get-result-folder-id agave job)
   :startdate       (or (util/to-utc (:startTime job)) "")
   :status          (job-status-translations (:status job) "")
   :wiki_url        ""})

(defn format-job
  ([agave jobs-enabled? app-info-map {app-id :appId :as job}]
     (let [app-info (app-info-map app-id {})]
       (format-job* agave
                    app-id
                    (app-listings/get-app-name app-info)
                    (:shortDescription app-info "")
                    job)))
  ([agave jobs-enabled? statuses app-info-map {app-id :appId :as job}]
     (let [app-info (app-info-map app-id {})]
       (assoc (format-job agave jobs-enabled? app-info-map job)
         :app-disabled (not (app-enabled? statuses jobs-enabled? app-info))))))

(defn format-job-submisison-response
  [agave submission job]
  (format-job* agave
               (:appId submission)
               (:appName submission)
               (:appDescription submission)
               job))

(defn translate-job-status
  [status]
  (get job-status-translations status))

(defn regenerate-job-submission
  [agave job]
  (let [app-id     (:appId job)
        app        (.getApp agave app-id)
        job-params (:parameters (params/format-params agave job app-id app))
        cfg-entry  (juxt (comp keyword :param_id) (comp :value :param_value))]
    {:app_id               app-id
     :name                 (:name job)
     :debug                false
     :notify               false
     :output_dir           (get-result-folder-id agave job)
     :create_output_subdir true
     :description          ""
     :config               (into {} (map cfg-entry job-params))}))
