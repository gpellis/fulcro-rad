(ns com.fulcrologic.rad.report
  #?(:cljs (:require-macros com.fulcrologic.rad.report))
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RENDERING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-layout [report-instance]
  (let [{::app/keys [runtime-atom]} (comp/any->app report-instance)
        layout-style (or (some-> report-instance comp/component-options ::layout-style) :default)
        layout       (some-> runtime-atom deref :com.fulcrologic.rad/controls ::style->layout layout-style)]
    (if layout
      (layout report-instance)
      (do
        (log/error "No layout function found for form layout style" layout-style)
        nil))))

(defn render-parameter-input [this parameter-key]
  (let [{::app/keys [runtime-atom]} (comp/any->app this)
        input-type  (some-> this comp/component-options ::parameters (get parameter-key))
        input-style :default                                ; TODO: Support parameter styles
        input       (some-> runtime-atom deref ::rad/controls ::parameter-type->style->input (get-in [input-type input-style]))]
    (if input
      (input this parameter-key)
      (do
        (log/error "No renderer installed to support parameter " parameter-key)
        nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOGIC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-report! [app TargetReportClass parameters]
  (let [{::keys [BodyItem source-attribute]} (comp/component-options TargetReportClass)
        path (conj (comp/get-ident TargetReportClass {}) source-attribute)]
    (log/info "Loading report" source-attribute
      (comp/component-name TargetReportClass)
      (comp/component-name BodyItem))
    (df/load! app source-attribute BodyItem {:params parameters :target path})))

(def global-events {})

(defn exit-report [{::uism/keys [fulcro-app] :as env}]
  (let [Report       (uism/actor-class env :actor/report)
        ;; TODO: Rename cancel-route to common RAD ns
        cancel-route (some-> Report comp/component-options ::cancel-route)]
    (if cancel-route
      (dr/change-route fulcro-app (or cancel-route []))
      (log/error "Don't know where to route on cancel. Add ::report/cancel-route to your form."))
    (uism/exit env)))

(defstatemachine report-machine
  {::uism/actors
   #{:actor/report}

   ::uism/aliases
   {}

   ::uism/states
   {:initial
    {::uism/handler (fn [env]
                      (let [{::uism/keys [event-data]} env
                            {::keys [action]} event-data]
                        (-> env
                          (uism/store ::action action)
                          (uism/activate :state/gathering-parameters))))}

    :state/gathering-parameters
    (merge global-events
      {::uism/events
       {:event/parameter-changed {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                   ;; NOTE: value at this layer is ALWAYS typed to the attribute.
                                                   ;; The rendering layer is responsible for converting the value to/from
                                                   ;; the representation needed by the UI component (e.g. string)
                                                   (let [{:keys [parameter value]} event-data
                                                         form-ident (uism/actor->ident env :actor/report)
                                                         path       (when (and form-ident parameter)
                                                                      (conj form-ident parameter))]
                                                     (when-not path
                                                       (log/error "Unable to record attribute change. Path cannot be calculated."))
                                                     (cond-> env
                                                       path (uism/apply-action assoc-in path value))))}
        :event/run               {::uism/handler (fn [{::uism/keys [fulcro-app state-map event-data] :as env}]
                                                   (let [Report         (uism/actor-class env :actor/report)
                                                         report-ident   (uism/actor->ident env :actor/report)
                                                         desired-params (some-> Report comp/component-options ::parameters keys set)
                                                         current-params (merge
                                                                          (select-keys (log/spy :info (get-in state-map report-ident)) (log/spy :info desired-params))
                                                                          event-data)]
                                                     (load-report! fulcro-app Report current-params)
                                                     env))}}})}})

(defn run-report!
  "Run a report with the current parameters"
  [this]
  (uism/trigger! this (comp/get-ident this) :event/run))

(defn req!
  ([sym options k pred?]
   (when-not (and (contains? options k) (pred? (get options k)))
     (throw (ex-info (str "defsc-report " sym " is missing or invalid option " k) {}))))
  ([sym options k]
   (when-not (contains? options k)
     (throw (ex-info (str "defsc-report " sym " is missing option " k) {})))))

(defn opt!
  [sym options k pred?]
  (when-not (pred? (get options k))
    (throw (ex-info (str "defsc-report " sym " has an invalid option " k) {}))))

(defn report-will-enter [app route-params report-class]
  (let [report-ident (comp/get-ident report-class {})]
    (uism/begin! app report-machine report-ident {:actor/report report-class})
    (dr/route-immediate (comp/get-ident report-class {}))))

(defn report-will-leave [_ _] true)

#?(:clj
   (defmacro defsc-report
     "Define a report. Just like defsc, but you do not specify query/ident/etc.

     Instead:

     ::report/BodyItem FulcroClass?
     ::report/columns (every? keyword? :kind vector?)
     ::report/column-headings (every? string? :kind vector?)
     ::report/source-attribute keyword?
     ::report/route string?
     ::report/parameters (map-of ui-keyword? rad-data-type?)

     NOTE: Parameters MUST have a `ui` namespace, like `:ui/show-inactive?`.

     If you elide the body, one will be generated for you.
     "
     [sym arglist & args]
     (let [this-sym (first arglist)
           {::keys [BodyItem columns source-attribute route parameters] :as options} (first args)
           subquery (cond
                      BodyItem `(comp/get-query ~BodyItem)
                      (seq columns) columns
                      :else (throw (ex-info "Reports must have columns or a BodyItem" {})))
           query    (into [{source-attribute subquery}]
                      (keys parameters))
           options  (assoc options
                      :route-segment [route]
                      :will-enter `(fn [app# route-params#] (report-will-enter app# route-params# ~sym))
                      :will-leave `report-will-leave
                      :query query
                      :ident (list 'fn [] [:component/id (keyword sym)]))
           body     (if (seq (rest args))
                      (rest args)
                      [`(render-layout ~this-sym)])]
       (req! sym options ::BodyItem)
       (req! sym options ::source-attribute keyword?)
       (req! sym options ::route string?)
       (opt! sym options ::parameters
         (fn [p] (and (map? p)
                   (every? #(and
                              (keyword? %)
                              (= "ui" (namespace %))) (keys p)))))
       `(comp/defsc ~sym ~arglist ~options ~@body))))
