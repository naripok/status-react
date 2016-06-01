(ns status-im.i18n
  (:require
    [status-im.translations.en :as en]))

(set! js/window.I18n (js/require "react-native-i18n"))
(set! (.-fallbacks js/I18n) true)
(set! (.-defaultSeparator js/I18n) "/")

(set! (.-translations js.I18n) (clj->js {:en en/translations}))

(defn label [path & options]
  (.t js/I18n (name path) (clj->js options)))

(defn label-pluralize [count path]
  (.p js/I18n count (name path)))

(comment
  (defn deep-merge [& maps]
    (if (every? map? maps)
      (apply merge-with deep-merge maps)
      (last maps)))

  (defn add-translations [new-translations]
    (let [translations (.-translations js/I18n)]
      (set! (.-translations js/I18n) (clj->js (deep-merge (js->clj translations) new-translations)))))
  )