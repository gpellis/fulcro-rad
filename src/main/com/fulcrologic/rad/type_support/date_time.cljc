(ns com.fulcrologic.rad.type-support.date-time
  "A common set of date/time functions for CLJC.  Libraries like `tick` are promising, and CLJC time is useful
  (and used by this ns), but cljc-time does not have an interface that is the same between the two languages,
  and tick is alpha (and often annoying)."
  #?(:cljs (:require-macros [ucv.lib.datetime :refer [compilation-inst]]))
  (:require
    ;; These two load locale definitions and timezone names
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [com.fulcrologic.guardrails.core :refer [>defn >def => ?]]
    [taoensso.timbre :as log]
    [cljc.java-time.instant :as instant]
    [cljc.java-time.day-of-week :as java-time.day-of-week]
    [cljc.java-time.local-date-time :as ldt]
    [cljc.java-time.local-date :as ld]
    [cljc.java-time.local-time :as lt]
    [cljc.java-time.zoned-date-time :as zdt]
    cljc.java-time.format.date-time-formatter
    [cljc.java-time.zone-id :as zone-id]
    [cljc.java-time.zone-offset :as zone-offset]
    [cljc.java-time.month :refer [january february march april may june july august september october
                                  november december]]
    #?@(:clj  []
        :cljs [[java.time :refer [Duration ZoneId LocalTime LocalDateTime LocalDate DayOfWeek Month ZoneOffset Instant]]
               [goog.date.duration :as g-duration]]))
  #?(:clj (:import java.io.Writer
                   [java.util Date]
                   [java.time DayOfWeek Duration Instant LocalDate LocalDateTime LocalTime Month MonthDay
                              OffsetDateTime OffsetTime Period Year YearMonth ZonedDateTime ZoneId ZoneOffset]
                   [java.time.zone ZoneRules]
                   [java.time.temporal TemporalAdjusters ChronoField ChronoUnit]
                   [com.cognitect.transit TransitFactory WriteHandler ReadHandler])))

(>def ::month (s/or :month #{january february march april may june july august september october
                             november december}))
(>def ::day (s/int-in 1 32))
(>def ::year (s/int-in 1970 3000))
(>def ::hour (s/int-in 0 24))
(>def ::minute (s/int-in 0 60))
(>def ::instant #(instance? Instant %))
(>def ::local-time #(instance? LocalTime %))
(>def ::local-date-time #(instance? LocalDateTime %))
(>def ::local-date #(instance? LocalDate %))
(>def ::zone-name (set (cljc.java-time.zone-id/get-available-zone-ids)))
(>def ::at inst?)
(>def ::day-of-week #{java-time.day-of-week/sunday
                      java-time.day-of-week/monday
                      java-time.day-of-week/tuesday
                      java-time.day-of-week/wednesday
                      java-time.day-of-week/thursday
                      java-time.day-of-week/friday
                      java-time.day-of-week/saturday})

(>defn new-date
  "Create a Date object from milliseconds (defaults to now)."
  ([]
   [=> inst?]
   #?(:clj  (Date.)
      :cljs (js/Date.)))
  ([millis]
   [int? => inst?]
   #?(:clj  (new Date millis)
      :cljs (js/Date. millis))))

(>defn now
  "Returns the current time as an inst."
  []
  [=> inst?]
  (new-date))

(defn inst->instant [i] (instant/of-epoch-milli (inst-ms i)))
(defn instant->inst [i] (new-date (instant/to-epoch-milli i)))

(def zone-region? #(= java.time.ZoneRegion (type %)))
(def date? #(= java.time.LocalDate (type %)))
(def date-time? #(= java.time.LocalDateTime (type %)))
(def date? #(= java.time.LocalDate (type %)))

(def mon-to-sunday [java-time.day-of-week/monday
                    java-time.day-of-week/tuesday
                    java-time.day-of-week/wednesday
                    java-time.day-of-week/thursday
                    java-time.day-of-week/friday
                    java-time.day-of-week/saturday
                    java-time.day-of-week/sunday])

(>defn html-date-string->local-date
  "Convert a standard HTML5 date input string to a local date"
  [s]
  [string? => date?]
  (LocalDate/parse s))

(>defn local-date->html-date-string
  "Convert a standard HTML5 date input string to a local date"
  [d]
  [date? => string?]
  (str d))

(>defn local-datetime->inst
  "Returns a UTC Clojure inst based on the date/time given as time in the named (ISO) zone (e.g. America/Los_Angeles)."
  ([zone-name local-dt]
   [::zone-name ::local-date-time => inst?]
   (let [z      (zone-id/of zone-name)
         zdt    (ldt/at-zone local-dt z)
         millis (instant/to-epoch-milli (zdt/to-instant zdt))]
     (new-date millis)))
  ([zone-name month day yyyy hh mm ss]
   [::zone-name int? int? int? int? int? int? => inst?]
   (let [local-dt (ldt/of yyyy month day hh mm ss)]
     (local-datetime->inst zone-name local-dt)))
  ([zone-name month day yyyy hh mm]
   [::zone-name int? int? int? int? int? => inst?]
   (local-datetime->inst zone-name month day yyyy hh mm 0)))

(>defn inst->local-datetime
  "Converts a UTC Instant into the correctly-offset (e.g. America/Los_Angeles) LocalDateTime."
  [zone-name inst]
  [::zone-name (s/or :inst inst?
                 :instant ::instant) => ::local-date-time]
  (let [z   (zone-id/of zone-name)
        i   (instant/of-epoch-milli (inst-ms inst))
        ldt (ldt/of-instant i z)]
    ldt))

(>defn html-datetime-string->inst
  [zone-name date-time-string]
  [::zone-name string? => inst?]
  (let [z   (zone-id/of zone-name)
        dt  (ldt/parse date-time-string)
        zdt (ldt/at-zone dt z)
        i   (zdt/to-instant zdt)]
    (new-date (instant/to-epoch-milli i))))

(>defn inst->html-datetime-string
  [zone-name inst]
  [::zone-name inst? => string?]
  (let [z         (zone-id/of zone-name)
        ldt       (ldt/of-instant (inst->instant inst) z)
        formatter cljc.java-time.format.date-time-formatter/iso-local-date-time]
    (ldt/format ldt formatter)))
