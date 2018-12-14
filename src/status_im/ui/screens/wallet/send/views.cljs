(ns status-im.ui.screens.wallet.send.views
  (:require-macros [status-im.utils.views :refer [defview letsubs]])
  (:require [clojure.string :as string]
            [re-frame.core :as re-frame]
            [status-im.i18n :as i18n]
            [status-im.ui.components.animation :as animation]
            [status-im.ui.components.icons.vector-icons :as vector-icons]
            [status-im.ui.components.react :as react]
            [status-im.ui.components.status-bar.view :as status-bar]
            [status-im.ui.components.styles :as components.styles]
            [status-im.ui.components.toolbar.actions :as actions]
            [status-im.ui.components.toolbar.view :as toolbar]
            [status-im.ui.components.tooltip.views :as tooltip]
            [status-im.ui.components.list.views :as list]
            [status-im.ui.components.list.styles :as list.styles]
            [status-im.ui.screens.chat.photos :as photos]
            [status-im.ui.screens.wallet.components.views :as wallet.components]
            [status-im.ui.screens.wallet.db :as wallet.db]
            [status-im.ui.screens.wallet.send.styles :as styles]
            [status-im.ui.screens.wallet.send.events :as events]
            [status-im.ui.screens.wallet.styles :as wallet.styles]
            [status-im.utils.money :as money]
            [status-im.utils.utils :as utils]
            [status-im.utils.ethereum.tokens :as tokens]
            [status-im.utils.ethereum.core :as ethereum]
            [status-im.utils.ethereum.ens :as ens]
            [taoensso.timbre :as log]
            [reagent.core :as reagent]
            [status-im.ui.components.colors :as colors]
            [status-im.ui.screens.wallet.utils :as wallet.utils]))

(defn- toolbar [flow title chat-id]
  (let [action (if (#{:chat :dapp} flow) actions/close-white actions/back-white)]
    [toolbar/toolbar {:style wallet.styles/toolbar}
     [toolbar/nav-button (action (if (= :chat flow)
                                   #(re-frame/dispatch [:chat.ui/navigate-to-chat chat-id {}])
                                   #(actions/default-handler)))]
     [toolbar/content-title {:color :white :font-weight :bold :font-size 17} title]]))

;; ----------------------------------------------------------------------
;; Step 1 choosing an address or contact to send the transaction to
;; ----------------------------------------------------------------------

(defn simple-tab-navigator
  "A simple tab navigator that that takes a map of tabs and the key of
  the starting tab 

  Example:
  (simple-tab-navigator
   {:main {:name \"Main\" :component (fn [] [react/text \"Hello\"])}
    :other {:name \"Other\" :component (fn [] [react/text \"Goodbye\"])}}
   :main)"
  [tab-map default-key]
  {:pre [(keyword? default-key)]}
  (let [tab-key (reagent/atom default-key)]
    (fn [tab-map _]
      (let [tab-name @tab-key]
        [react/view {:flex 1}
         ;; tabs row
         [react/view {:flex-direction :row}
          (map (fn [[key {:keys [name component]}]]
                 (let [current? (= key tab-name)]
                   ^{:key (str key)}
                   [react/view {:flex             1
                                :background-color colors/black-transparent}
                    [react/touchable-highlight {:on-press #(reset! tab-key key)
                                                :disabled current?}
                     [react/view {:height              44
                                  :align-items         :center
                                  :justify-content     :center
                                  :border-bottom-width 2
                                  :border-bottom-color (if current? colors/white colors/transparent)}
                      [react/text {:style {:color     (if current? colors/white colors/white-transparent)
                                           :font-size 15}} name]]]]))
               tab-map)]
         (when-let [component-thunk (some-> tab-map tab-name :component)]
           [component-thunk])]))))

(defn- address-button [{:keys [disabled? on-press underlay-color background-color]} content]
  [react/touchable-highlight {:underlay-color underlay-color
                              :disabled       disabled?
                              :on-press       on-press
                              :style          {:height           44
                                               :background-color background-color
                                               :border-radius    8
                                               :flex             1
                                               :align-items      :center
                                               :justify-content  :center
                                               :margin           3}}
   content])

(defn open-qr-scanner [chain text-input transaction]
  (.blur @text-input)
  (re-frame/dispatch [:navigate-to :recipient-qr-code
                      {:on-recipient
                       (fn [qr-data]
                         (if-let [parsed-qr-data (events/extract-qr-code-details chain qr-data)]
                           (let [{:keys [chain-id]} parsed-qr-data
                                 tx-data            (events/qr-data->transaction-data parsed-qr-data)]
                             (if (= chain-id (ethereum/chain-keyword->chain-id chain))
                               (swap! transaction merge tx-data)
                               (utils/show-popup (i18n/label :t/error)
                                                 (i18n/label :t/wallet-invalid-chain-id {:data  qr-data
                                                                                         :chain chain-id}))))
                           (utils/show-confirmation
                            {:title               (i18n/label :t/error)
                             :content             (i18n/label :t/wallet-invalid-address {:data qr-data})
                             :cancel-button-text  (i18n/label :t/see-it-again)
                             :confirm-button-text (i18n/label :t/got-it)
                             :on-cancel           (partial open-qr-scanner chain text-input transaction)})))}]))

(defn update-recipient [chain transaction error-message value]
  (if (ens/is-valid-eth-name? value)
    (do (ens/get-addr (get @re-frame.db/app-db :web3)
                      (get ens/ens-registries chain)
                      value
                      #(if (ethereum/address? %)
                         (swap! transaction assoc :to-ens value :to %)
                         (reset! error-message (i18n/label :t/error-unknown-ens-name)))))
    (do (swap! transaction assoc :to value)
        (reset! error-message nil))))

(defn choose-address-view
  "A view that allows you to choose an address"
  [{:keys [chain transaction on-address]}]
  {:pre [(keyword? chain) (fn? on-address)]}
  (fn []
    (let [error-message (reagent/atom nil)
          text-input    (atom nil)]
      (fn []
        [react/view {:flex 1}
         [react/view {:flex 1}]
         [react/view styles/centered
          (when @error-message
            [tooltip/tooltip @error-message {:color        colors/white
                                             :font-size    12
                                             :bottom-value 15}])
          [react/text-input
           {:on-change-text         (partial update-recipient chain transaction error-message)
            :auto-focus             true
            :auto-capitalize        :none
            :auto-correct           false
            :placeholder            (i18n/label :t/address-or-ens-placeholder)
            :placeholder-text-color colors/blue-shadow
            :multiline              true
            :max-length             84
            :ref                    #(reset! text-input %)
            :default-value          (or (:to-ens @transaction) (:to @transaction))
            :selection-color        colors/green
            :accessibility-label    :recipient-address-input
            :style                  styles/choose-recipient-text-input}]]
         [react/view {:flex 1}]
         [react/view {:flex-direction :row
                      :padding        3}
          [address-button {:underlay-color   colors/white-transparent
                           :background-color colors/black-transparent
                           :on-press         #(react/get-from-clipboard
                                               (fn [addr]
                                                 (when (and addr (not (string/blank? addr)))
                                                   (swap! transaction assoc :to (string/trim addr)))))}
           [react/view {:flex-direction     :row
                        :padding-horizontal 18}
            [vector-icons/icon :icons/paste {:color colors/white-transparent}]
            [react/view {:flex            1
                         :flex-direction  :row
                         :justify-content :center}
             [react/text {:style {:color       colors/white
                                  :font-size   15
                                  :line-height 22}}
              (i18n/label :t/paste)]]]]
          [address-button {:underlay-color colors/white-transparent
                           :background-color colors/black-transparent
                           :on-press
                           (fn []
                             (re-frame/dispatch
                              [:request-permissions {:permissions [:camera]
                                                     :on-allowed (partial open-qr-scanner chain text-input transaction)
                                                     :on-denied
                                                     #(utils/set-timeout
                                                       (fn []
                                                         (utils/show-popup (i18n/label :t/error)
                                                                           (i18n/label :t/camera-access-error)))
                                                       50)}]))}
           [react/view {:flex-direction     :row
                        :padding-horizontal 18}
            [vector-icons/icon :icons/qr {:color colors/white-transparent}]
            [react/view {:flex            1
                         :flex-direction  :row
                         :justify-content :center}
             [react/text {:style {:color       colors/white
                                  :font-size   15
                                  :line-height 22}}
              (i18n/label :t/scan)]]]]
          (let [disabled? (string/blank? (:to @transaction))]
            [address-button {:disabled?        disabled?
                             :underlay-color   colors/black-transparent
                             :background-color (if disabled? colors/blue colors/white)
                             :on-press
                             #(events/chosen-recipient chain (:to @transaction) on-address
                                                       (fn on-error [_]
                                                         (reset! error-message (i18n/label :t/invalid-address))))}
             [react/text {:style {:color       (if disabled? colors/white colors/blue)
                                  :font-size   15
                                  :line-height 22}}
              (i18n/label :t/next)]])]]))))

(defn info-page [message]
  [react/view {:style {:flex             1
                       :align-items      :center
                       :justify-content  :center
                       :background-color colors/blue}}
   [vector-icons/icon :icons/info {:color colors/white}]
   [react/text {:style {:max-width   144
                        :margin-top  15
                        :color       colors/white
                        :font-size   15
                        :text-align  :center
                        :line-height 22}}
    message]])

(defn render-contact [on-contact contact]
  {:pre [(fn? on-contact) (map? contact) (:address contact)]}
  [react/touchable-highlight {:underlay-color colors/white-transparent
                              :on-press       #(on-contact contact)}
   [react/view {:flex           1
                :flex-direction :row
                :padding-right  23
                :padding-left   16
                :padding-top    12}
    [react/view {:margin-top 3}
     [photos/photo (:photo-path contact) {:size list.styles/image-size}]]
    [react/view {:margin-left 16
                 :flex        1}
     [react/view {:accessibility-label :contact-name-text
                  :margin-bottom       2}
      [react/text {:style {:font-size   15
                           :font-weight "500"
                           :line-height 22
                           :color       colors/white}}
       (:name contact)]]
     [react/text {:style               {:font-size   15
                                        :line-height 22
                                        :color       colors/white-transparent}
                  :accessibility-label :contact-address-text}
      (ethereum/normalized-address (:address contact))]]]])

(defn choose-contact-view [{:keys [contacts on-contact]}]
  {:pre [(every? map? contacts) (fn? on-contact)]}
  (if (empty? contacts)
    (info-page (i18n/label :t/wallet-no-contacts))
    [react/view {:flex 1}
     [list/flat-list {:data      contacts
                      :key-fn    :address
                      :render-fn (partial
                                  render-contact
                                  on-contact)}]]))

(defn- choose-address-contact [{:keys [modal? contacts transaction network network-status]}]
  (let [transaction     (reagent/atom transaction)
        chain           (ethereum/network->chain-keyword network)
        native-currency (tokens/native-currency chain)
        online?         (= :online network-status)]
    [wallet.components/simple-screen {:avoid-keyboard? (not modal?)
                                      :status-bar-type (if modal? :modal-wallet :wallet)}
     [toolbar :wallet (i18n/label :t/send-to) nil]
     [simple-tab-navigator
      {:address  {:name      (i18n/label :t/wallet-address-tab-title)
                  :component (choose-address-view
                              {:chain       chain
                               :transaction transaction
                               :on-address  #(re-frame/dispatch [:navigate-to :wallet-choose-amount
                                                                 {:transaction     (swap! transaction assoc :to %)
                                                                  :native-currency native-currency
                                                                  :modal?          modal?}])})}
       :contacts {:name      (i18n/label :t/wallet-contacts-tab-title)
                  :component (partial choose-contact-view
                                      {:contacts   contacts
                                       :on-contact (fn [{:keys [address] :as contact}]
                                                     (re-frame/dispatch
                                                      [:navigate-to :wallet-choose-amount
                                                       {:modal?          modal?
                                                        :native-currency native-currency
                                                        :contact         contact
                                                        :transaction     (swap! transaction assoc :to address)}]))})}}
      :address]]))

;; ----------------------------------------------------------------------
;; Step 2 choosing an amount and token to send
;; ----------------------------------------------------------------------

(declare network-fees)

;; worthy abstraction
(defn- anim-ref-send
  "Call one of the methods in an animation ref.
   Takes an animation ref (a map of keys to animation tiggering methods)
   a keyword that should equal one of the keys in the map  and optional args to be sent to the animation.

   Example:
    (anim-ref-send slider-ref :open!)
    (anim-ref-send slider-ref :move-top-left! 25 25)"
  [anim-ref signal & args]
  (when anim-ref
    (assert (get anim-ref signal)
            (str "Key " signal " was not found in animation ref. Should be in "
                 (pr-str (keys anim-ref)))))
  (some-> anim-ref (get signal) (apply args)))

(defn static-modal [children]
  (let [modal-screen-bg-color (colors/alpha colors/black 0.7)]
    [react/view {:style
                 {:position         :absolute
                  :top              0
                  :bottom           0
                  :left             0
                  :right            0
                  :z-index          1
                  :background-color modal-screen-bg-color}}
     children]))

(defn- slide-up-modal
  "Creates a modal that slides up from the bottom of the screen and
  responds to a swipe down gesture to dismiss 
   
  The modal initially renders in the closed position.

  It takes an options map and the react child to be displayed in the
  modal. 

  Options:
    :anim-ref - takes a function that will be called with a map of
        animation methods.
    :swipe-dismiss? - a boolean that determines whether the modal screen
        should be dismissed on swipe down gesture

  This slide-up-modal will callback the `anim-ref` fn and provides a
  map with 2 animation methods:

  :open!  - opens and displays the modal
  :close! - closes the modal"
  [{:keys [anim-ref swipe-dismiss?]} children]
  {:pre [(fn? anim-ref)]}
  (let [window-height (:height (react/get-dimensions "window") 1000)

        bottom-position  (animation/create-value (- window-height))

        modal-screen-bg-color
        (animation/interpolate bottom-position
                               {:inputRange [(- window-height) 0]
                                :outputRange [colors/black
                                              (colors/alpha colors/black 0.7)]})
        modal-screen-top
        (animation/interpolate bottom-position
                               {:inputRange [(- window-height)
                                             (+ (- window-height) 1)
                                             0]
                                :outputRange [window-height -200 -200]})

        vertical-slide-to (fn [view-bottom]
                            (animation/start
                             (animation/timing bottom-position {:toValue  view-bottom
                                                                :duration 500})))
        open-panel! #(vertical-slide-to 0)
        close-panel! #(vertical-slide-to (- window-height))
        ;; swipe-down-panhandler
        swipe-down-handlers
        (when swipe-dismiss?
          (js->clj
           (.-panHandlers
            (.create react/pan-responder
                     #js {:onMoveShouldSetPanResponder
                          (fn [e g]
                            (when-let [distance (.-dy g)]
                              (< 50 distance)))
                          :onMoveShouldSetPanResponderCapture
                          (fn [e g]
                            (when-let [distance (.-dy g)]
                              (< 50 distance)))
                          :onPanResponderRelease  (fn [e g]
                                                    (when-let [distance (.-dy g)]
                                                      (when (< 200 distance)
                                                        (close-panel!))))}))))]
    (anim-ref {:open! open-panel!
               :close! close-panel!})
    (fn [{:keys [anim-ref] :as opts} children]
      [react/animated-view (merge
                            {:style
                             {:position :absolute
                              :top      modal-screen-top
                              :bottom   0
                              :left     0
                              :right    0
                              :z-index  1
                              :background-color modal-screen-bg-color}}
                            swipe-down-handlers)
       [react/touchable-highlight {:on-press (fn [] (close-panel!))
                                   :style {:flex 1}}
        [react/view]]
       [react/animated-view {:style
                             {:position :absolute
                              :left     0
                              :right    0
                              :z-index  2
                              :bottom   bottom-position}}
        children]])))

(defn- custom-gas-panel-action [{:keys [label active on-press icon background-color]} child]
  {:pre [label (boolean? active) on-press icon background-color]}
  [react/view {:style {:flex-direction     :row
                       :padding-horizontal 22
                       :padding-vertical   11
                       :align-items        :center}}
   [react/touchable-highlight
    {:disabled active
     :on-press on-press}
    [react/animated-view {:style {:border-radius    21
                                  :width            40
                                  :height           40
                                  :justify-content  :center
                                  :align-items      :center
                                  :background-color background-color}}
     [vector-icons/icon icon {:color (if active colors/white colors/gray)}]]]
   [react/touchable-highlight
    {:disabled active
     :on-press on-press
     :style    {:flex 1}}
    [react/text {:style {:color        colors/black
                         :font-size    17
                         :padding-left 17
                         :line-height  40}}
     label]]
   child])

(defn- custom-gas-edit
  [_opts]
  (let [gas-error (reagent/atom nil)
        gas-price-error (reagent/atom nil)]
    (fn [{:keys [on-gas-input-change
                 on-gas-price-input-change
                 gas-input
                 gas-price-input]}]
      [react/view {:style {:padding-horizontal 22
                           :padding-vertical   11}}
       [react/text (i18n/label :t/gas-price)]
       (when @gas-price-error
         [react/view {:style {:z-index 100}}
          [tooltip/tooltip @gas-price-error
           {:color        colors/blue-light
            :font-size    12
            :bottom-value -3}]])
       [react/view {:style {:border-radius      8
                            :background-color   colors/gray-light
                            :padding-vertical   16
                            :padding-horizontal 16
                            :flex-direction     :row
                            :align-items        :center
                            :margin-vertical    7}}
        [react/text-input {:keyboard-type  :numeric
                           :placeholder    "0"
                           :on-change-text (fn [x]
                                             (if-not (money/bignumber x)
                                               (reset! gas-price-error (i18n/label :t/invalid-number-format))
                                               (reset! gas-price-error nil))
                                             (on-gas-price-input-change x))
                           :default-value  gas-price-input
                           :style          {:font-size 15
                                            :flex      1}}]
        [react/text (i18n/label :t/gwei)]]
       [react/text {:style {:color colors/gray
                            :font-size 12}}
        (i18n/label :t/gas-cost-explanation)]
       [react/text {:style {:margin-top 22}} (i18n/label :t/gas-limit)]
       (when @gas-error
         [react/view {:style {:z-index 100}}
          [tooltip/tooltip @gas-error
           {:color        colors/blue-light
            :font-size    12
            :bottom-value -3}]])
       [react/view {:style {:border-radius      8
                            :background-color   colors/gray-light
                            :padding-vertical   16
                            :padding-horizontal 16
                            :flex-direction     :row
                            :align-items        :flex-end
                            :margin-vertical    7}}
        [react/text-input {:keyboard-type  :numeric
                           :placeholder    "0"
                           :on-change-text (fn [x]
                                             (if-not (money/bignumber x)
                                               (reset! gas-error (i18n/label :t/invalid-number-format))
                                               (reset! gas-error nil))
                                             (on-gas-input-change x))
                           :default-value  gas-input
                           :style          {:font-size 15
                                            :flex      1}}]]
       [react/text {:style {:color     colors/gray
                            :font-size 12}}
        (i18n/label :t/gas-limit-explanation)]])))

(defn custom-gas-derived-state [{:keys [gas-input gas-price-input custom-open?]}
                                {:keys [custom-gas custom-gas-price
                                        optimal-gas optimal-gas-price
                                        gas-gas-price->fiat
                                        fiat-currency]}]
  (let [custom-input-gas
        (or (when (not (string/blank? gas-input))
              (money/bignumber gas-input))
            custom-gas
            optimal-gas)
        custom-input-gas-price
        (or (when (not (string/blank? gas-price-input))
              (money/->wei :gwei gas-price-input))
            custom-gas-price
            optimal-gas-price)]
    {:optimal-fiat-price
     (str "~ " (:symbol fiat-currency)
          (gas-gas-price->fiat {:gas optimal-gas
                                :gas-price optimal-gas-price}))
     :custom-fiat-price
     (if custom-open?
       (str "~ " (:symbol fiat-currency)
            (gas-gas-price->fiat {:gas custom-input-gas
                                  :gas-price custom-input-gas-price}))
       (str "..."))
     :gas-price-input-value
     (str (or gas-price-input
              (some->> custom-gas-price (money/wei-> :gwei))
              (some->> optimal-gas-price (money/wei-> :gwei))))
     :gas-input-value
     (str (or gas-input custom-gas optimal-gas))
     :gas-map-for-submit
     (when custom-open?
       {:gas custom-input-gas :gas-price custom-input-gas-price})}))

;; Choosing the gas amount
(defn custom-gas-input-panel [{:keys [custom-gas custom-gas-price
                                      optimal-gas optimal-gas-price
                                      gas-gas-price->fiat on-submit] :as opts}]
  {:pre [optimal-gas optimal-gas-price gas-gas-price->fiat on-submit]}
  (let [custom-open? (and custom-gas custom-gas-price)
        state-atom   (reagent.core/atom {:custom-open?    (boolean custom-open?)
                                         :gas-input       nil
                                         :gas-price-input nil})

        ;; slider animations
        slider-height (animation/create-value (if custom-open? 290 0))
        slider-height-to #(animation/start
                           (animation/timing slider-height {:toValue  %
                                                            :duration 500}))

        optimal-button-bg-color
        (animation/interpolate slider-height
                               {:inputRange  [0 200 290]
                                :outputRange [colors/blue colors/gray-light colors/gray-light]})

        custom-button-bg-color
        (animation/interpolate slider-height
                               {:inputRange  [0 200 290]
                                :outputRange [colors/gray-light colors/blue colors/blue]})

        open-slider!  #(do
                         (slider-height-to 290)
                         (swap! state-atom assoc :custom-open? true))
        close-slider! #(do
                         (slider-height-to 0)
                         (swap! state-atom assoc :custom-open? false))]
    (fn [opts]
      (let [{:keys [optimal-fiat-price
                    custom-fiat-price
                    gas-price-input-value
                    gas-input-value
                    gas-map-for-submit]}
            (custom-gas-derived-state @state-atom opts)]
        [react/view {:style {:background-color        colors/white
                             :border-top-left-radius  8
                             :border-top-right-radius 8}}
         [react/view {:style {:justify-content :center
                              :padding-top     22
                              :padding-bottom  7}}
          [react/text
           {:style {:color colors/black
                    :font-size   22
                    :line-height 28
                    :font-weight :bold
                    :text-align  :center}}
           (i18n/label :t/network-fee-settings)]
          [react/text
           {:style {:color              colors/gray
                    :font-size          15
                    :line-height        22
                    :text-align         :center
                    :padding-horizontal 45
                    :padding-vertical   8}}
           (i18n/label :t/network-fee-explanation)]]
         [react/view {:style {:border-top-width 1
                              :border-top-color colors/black-transparent
                              :padding-top      11
                              :padding-bottom   7}}
          (custom-gas-panel-action {:icon             :icons/time
                                    :label            (i18n/label :t/optimal-gas-option)
                                    :on-press         close-slider!
                                    :background-color optimal-button-bg-color
                                    :active           (not (:custom-open? @state-atom))}
                                   [react/text {:style {:color        colors/gray
                                                        :font-size    17
                                                        :padding-left 17
                                                        :line-height  20}}
                                    optimal-fiat-price])
          (custom-gas-panel-action {:icon             :icons/sliders
                                    :label            (i18n/label :t/custom-gas-option)
                                    :on-press         open-slider!
                                    :background-color custom-button-bg-color
                                    :active           (:custom-open? @state-atom)}
                                   [react/text {:style {:color        colors/gray
                                                        :font-size    17
                                                        :padding-left 17
                                                        :line-height  20
                                                        :text-align   :center
                                                        :min-width    60}}
                                    custom-fiat-price])
          [react/animated-view {:style {:background-color colors/white
                                        :height           slider-height
                                        :overflow         :hidden}}
           [custom-gas-edit
            {:on-gas-price-input-change #(when (money/bignumber %)
                                           (swap! state-atom assoc :gas-price-input %))
             :on-gas-input-change       #(when (money/bignumber %)
                                           (swap! state-atom assoc :gas-input %))
             :gas-price-input           gas-price-input-value
             :gas-input                 gas-input-value}]]
          [react/view {:style {:flex-direction   :row
                               :justify-content  :center
                               :padding-vertical 16}}
           [react/touchable-highlight
            {:on-press #(on-submit gas-map-for-submit)
             :style    {:padding-horizontal 39
                        :padding-vertical   12
                        :border-radius      8
                        :background-color   colors/blue-light}}
            [react/text {:style {:font-size   15
                                 :line-height 22
                                 :color       colors/blue}}
             (i18n/label :t/update)]]]]]))))

;; Choosing the asset

(defn white-toolbar [modal? title]
  (let [action (if modal? actions/close actions/back)]
    [toolbar/toolbar {:style {:background-color    colors/white
                              :border-bottom-width 1
                              :border-bottom-color colors/black-transparent}}
     [toolbar/nav-button (action (if modal?
                                   #(re-frame/dispatch [:wallet/discard-transaction-navigate-back])
                                   #(actions/default-handler)))]
     [toolbar/content-title {:color       colors/black
                             :font-size   17
                             :font-weight :bold} title]]))

(defn- render-token-item [{:keys [name icon decimals amount] :as coin}]
  [list/item
   [list/item-image icon]
   [list/item-content
    [react/text {:style {:margin-right 10, :color colors/black}} name]
    [list/item-secondary (str (wallet.utils/format-amount amount decimals)
                              " "
                              (wallet.utils/display-symbol coin))]]])

(defview choose-asset []
  (letsubs [assets [:wallet/transferrable-assets-with-amount]
            {:keys [on-asset]} [:get-screen-params :wallet-choose-asset]]
    [react/keyboard-avoiding-view {:flex             1
                                   :background-color colors/white}
     [status-bar/status-bar {:type :modal-white}]
     [white-toolbar false (i18n/label :t/choose-asset)]
     [react/view {:style (assoc components.styles/flex :background-color :white)}
      [list/flat-list {:default-separator? false ;true
                       :data               assets
                       :key-fn             (comp str :symbol)
                       :render-fn          #(do
                                              [react/touchable-highlight {:on-press       (fn [] (on-asset %))
                                                                          :underlay-color colors/black-transparent}
                                               (render-token-item %)])}]]]))

(defn show-current-asset [{:keys [name icon decimals amount] :as token}]
  [react/view {:style {:flex-direction     :row,
                       :justify-content    :center
                       :padding-horizontal 21
                       :padding-vertical   12}}
   [list/item-image icon]
   [react/view {:margin-horizontal 9
                :flex              1}
    [list/item-content
     [react/text {:style {:margin-right 10,
                          :font-weight  "500"
                          :font-size    15
                          :color        colors/white}} name]
     [react/text {:style           {:font-size   14
                                    :padding-top 4
                                    :color       colors/white-transparent}
                  :ellipsize-mode  :middle
                  :number-of-lines 1}
      (str (wallet.utils/format-amount amount decimals)
           " "
           (wallet.utils/display-symbol token))]]]
   list/item-icon-forward])

;; TODOs
;; consistent input validation throughout looking at wallet.db/parse-amount
;; handle incoming error text :amount-error ??
;; consider :amount-text
;; use incoming gas-price
;; look at how callers are invoking send-transaction status-im.chat.commands.impl.transactions
;; look at what happens to gas-price on token change? Nothing I suspect
;; look at initial network fees

(defn fetch-token [all-tokens network token-symbol]
  {:pre [(map? all-tokens) (map? network)]}
  (when (keyword? token-symbol)
    (tokens/asset-for all-tokens
                      (ethereum/network->chain-keyword network)
                      token-symbol)))

(defn create-initial-state [{:keys [symbol decimals]} amount]
  {:input-amount  (when amount
                    (when-let [amount' (money/internal->formatted amount symbol decimals)]
                      (str amount')))
   :inverted      false
   :edit-gas      false
   :error-message nil})

(defn input-currency-symbol [{:keys [inverted] :as state} {:keys [symbol] :as coin} {:keys [code]}]
  {:pre [(boolean? inverted) (keyword? symbol) (string? code)]}
  (if-not (:inverted state) (wallet.utils/display-symbol coin) code))

(defn converted-currency-symbol [{:keys [inverted] :as state} {:keys [symbol] :as coin} {:keys [code]}]
  {:pre [(boolean? inverted) (keyword? symbol) (string? code)]}
  (if (:inverted state) (wallet.utils/display-symbol coin) code))

(defn token->fiat-conversion [prices token fiat-currency value]
  {:pre [(map? prices) (map? token) (map? fiat-currency) value]}
  (when-let [price (get-in prices [(:symbol token)
                                   (-> fiat-currency :code keyword)
                                   :price])]
    (some-> value
            money/bignumber
            (money/crypto->fiat price))))

(defn fiat->token-conversion [prices token fiat-currency value]
  {:pre [(map? prices) (map? token) (map? fiat-currency) value]}
  (when-let [price (get-in prices [(:symbol token)
                                   (-> fiat-currency :code keyword)
                                   :price])]
    (some-> value
            money/bignumber
            (.div (money/bignumber price)))))

(defn valid-input-amount? [input-amount]
  (and (not (string/blank? input-amount))
       (not (:error (wallet.db/parse-amount input-amount 100)))))

(defn converted-currency-amount [{:keys [input-amount inverted]} token fiat-currency prices]
  (when (valid-input-amount? input-amount)
    (if-not inverted
      (some-> (token->fiat-conversion prices token fiat-currency input-amount)
              (money/with-precision 2))
      (some-> (fiat->token-conversion prices token fiat-currency input-amount)
              (money/with-precision 8)))))

(defn converted-currency-phrase [state token fiat-currency prices]
  (str (if-let [amount-bn (converted-currency-amount state token fiat-currency prices)]
         (str amount-bn)
         "0")
       " " (converted-currency-symbol state token fiat-currency)))

(defn current-token-input-amount [{:keys [input-amount inverted] :as state} token fiat-currency prices]
  {:pre [(map? state) (map? token) (map? fiat-currency) (map? prices)]}
  (when input-amount
    (when-let [amount-bn (if inverted
                           (fiat->token-conversion prices token fiat-currency input-amount)
                           (money/bignumber input-amount))]
      amount-bn
      (money/formatted->internal amount-bn (:symbol token) (:decimals token)))))

(defn update-input-errors [{:keys [input-amount inverted] :as state} token fiat-currency prices]
  {:pre [(map? state) (map? token) (map? fiat-currency) (map? prices)]}
  (let [{:keys [_value error]}
        (wallet.db/parse-amount input-amount
                                (if inverted 2 (:decimals token)))]
    (if-let [error-msg
             (cond
               error error
               (not (money/sufficient-funds? (current-token-input-amount state token fiat-currency prices)
                                             (:amount token)))
               (i18n/label :t/wallet-insufficient-funds)
               :else nil)]
      (assoc state :error-message error-msg)
      state)))

(defn update-input-amount [state input-str token fiat-currency prices]
  {:pre [(map? state) (map? token) (map? fiat-currency) (map? prices)]}
  (cond-> (-> state
              (assoc :input-amount input-str)
              (dissoc :error-message))
    (not (string/blank? input-str))
    (update-input-errors token fiat-currency prices)))

(defn max-fee [{:keys [gas gas-price]}]
  (if (and gas gas-price)
    (money/wei->ether (.times gas gas-price))
    0))

(defn network-fees [prices token fiat-currency gas-ether-price]
  (some-> (token->fiat-conversion prices token fiat-currency gas-ether-price)
          (money/with-precision 2)))

(defn fetch-optimal-gas [symbol cb]
  (ethereum/gas-price
   (:web3 @re-frame.db/app-db)
   (fn [_ gas-price]
     (when gas-price
       (cb {:optimal-gas (ethereum/estimate-gas symbol)
            :optimal-gas-price gas-price})))))

(defn optimal-gas-present? [{:keys [optimal-gas optimal-gas-price]}]
  (and optimal-gas optimal-gas-price))

(defn current-gas [{:keys [gas gas-price optimal-gas optimal-gas-price]}]
  {:gas (or gas optimal-gas) :gas-price (or gas-price optimal-gas-price)})

(defn refresh-optimal-gas [symbol tx-atom]
  (fetch-optimal-gas symbol
                     (fn [res]
                       (swap! tx-atom merge res))))

(defn choose-amount-token-helper [{:keys [network
                                          native-currency
                                          all-tokens
                                          contact
                                          transaction]}]
  {:pre [(map? native-currency)]}
  (let [tx-atom                (reagent/atom transaction)
        coin                   (or (fetch-token all-tokens network (:symbol transaction))
                                   native-currency)
        state-atom             (reagent/atom (create-initial-state coin (:amount transaction)))
        network-fees-modal-ref (atom nil)
        open-network-fees!     #(anim-ref-send @network-fees-modal-ref :open!)
        close-network-fees!    #(anim-ref-send @network-fees-modal-ref :close!)]
    (when-not (optimal-gas-present? transaction)
      (refresh-optimal-gas
       (some :symbol [transaction native-currency]) tx-atom))
    (fn [{:keys [balance network prices fiat-currency
                 native-currency all-tokens modal?]}]
      (let [symbol (some :symbol [@tx-atom native-currency])
            coin  (-> (tokens/asset-for all-tokens (ethereum/network->chain-keyword network) symbol)
                      (assoc :amount (get balance symbol (money/bignumber 0))))
            gas-gas-price->fiat
            (fn [gas-map]
              (network-fees prices coin fiat-currency (max-fee gas-map)))
            update-amount-field #(swap! state-atom update-input-amount % coin fiat-currency prices)]
        [wallet.components/simple-screen {:avoid-keyboard? (not modal?)
                                          :status-bar-type (if modal? :modal-wallet :wallet)}
         [toolbar :wallet (i18n/label :t/send-amount) nil]
         (if (empty? balance)
           (info-page (i18n/label :t/wallet-no-assets-enabled))
           (let [{:keys [error-message input-amount] :as state} @state-atom
                 input-symbol     (input-currency-symbol state coin fiat-currency)
                 converted-phrase (converted-currency-phrase state coin fiat-currency prices)]
             [react/view {:flex 1}
              ;; network fees modal
              (when (optimal-gas-present? @tx-atom)
                [slide-up-modal {:anim-ref #(reset! network-fees-modal-ref %)
                                 :swipe-dismiss? true}
                 [custom-gas-input-panel
                  (-> (select-keys @tx-atom [:gas :gas-price :optimal-gas :optimal-gas-price])
                      (assoc
                       :fiat-currency fiat-currency
                       :gas-gas-price->fiat gas-gas-price->fiat
                       :on-submit (fn [{:keys [gas gas-price]}]
                                    (when (and gas gas-price)
                                      (swap! tx-atom assoc :gas gas :gas-price gas-price))
                                    (close-network-fees!))))]])
              [react/touchable-highlight {:style          {:background-color colors/black-transparent}
                                          :on-press       #(re-frame/dispatch
                                                            [:navigate-to :wallet-choose-asset
                                                             {:on-asset (fn [{:keys [symbol]}]
                                                                          (when symbol
                                                                            (if-not (= symbol (:symbol @tx-atom))
                                                                              (update-amount-field nil))
                                                                            (swap! tx-atom assoc :symbol symbol)
                                                                            (refresh-optimal-gas symbol tx-atom))
                                                                          (re-frame/dispatch [:navigate-back]))}])
                                          :underlay-color colors/white-transparent}
               [show-current-asset coin]]
              [react/view {:flex 1}
               [react/view {:flex 1}]
               [react/view {:justify-content :center
                            :align-items     :center
                            :flex-direction  :row}
                (when error-message
                  [tooltip/tooltip error-message {:color        colors/white
                                                  :font-size    12
                                                  :bottom-value 15}])
                [react/text-input
                 {:on-change-text         update-amount-field
                  :keyboard-type          :numeric
                  :accessibility-label    :amount-input
                  :auto-focus             true
                  :auto-capitalize        :none
                  :auto-correct           false
                  :placeholder            "0"
                  :placeholder-text-color colors/blue-shadow
                  :multiline              true
                  :max-length             20
                  :default-value          input-amount
                  :selection-color        colors/green
                  :style                  {:color              colors/white
                                           :font-size          30
                                           :font-weight        :bold
                                           :padding-horizontal 10
                                           :max-width          290}}]
                [react/text {:style {:color       (if (not (string/blank? input-amount))
                                                    colors/white
                                                    colors/blue-shadow)
                                     :font-size   30
                                     :font-weight :bold}}
                 input-symbol]]
               [react/view {}
                [react/text {:style {:text-align  :center
                                     :margin-top  16
                                     :font-size   15
                                     :line-height 22
                                     :color       colors/blue-shadow}}
                 converted-phrase]]
               [react/view {:justify-content :center
                            :flex-direction  :row}
                [react/touchable-highlight {:on-press open-network-fees!
                                            :style    {:background-color   colors/black-transparent
                                                       :padding-horizontal 13
                                                       :padding-vertical   7
                                                       :margin-top         1
                                                       :border-radius      8
                                                       :opacity            (if (valid-input-amount? input-amount) 1 0)}}
                 [react/text {:style {:color       colors/white
                                      :font-size   15
                                      :line-height 22}}
                  (i18n/label :t/network-fee-amount {:amount   (when (optimal-gas-present? @tx-atom)
                                                                 (or (gas-gas-price->fiat (current-gas @tx-atom)) "0"))
                                                     :currency (:code fiat-currency)})]]]
               [react/view {:flex 1}]

               [react/view {:flex-direction :row
                            :padding        3}
                [address-button {:underlay-color   colors/white-transparent
                                 :background-color colors/black-transparent
                                 :on-press         #(swap! state-atom update :inverted not)}
                 [react/view {:flex-direction :row}
                  [react/text {:style {:color colors/white
                                       :font-size     15
                                       :line-height   22
                                       :padding-right 10}}
                   (:code fiat-currency)]
                  [vector-icons/icon :icons/change {:color colors/white-transparent}]
                  [react/text {:style {:color        colors/white
                                       :font-size    15
                                       :line-height  22
                                       :padding-left 11}}
                   (wallet.utils/display-symbol coin)]]]
                (let [disabled? (string/blank? input-amount)]
                  [address-button {:disabled?        disabled?
                                   :underlay-color   colors/black-transparent
                                   :background-color (if disabled? colors/blue colors/white)
                                   :token            coin
                                   :on-press         #(re-frame/dispatch [:navigate-to :wallet-txn-overview
                                                                          {:modal?      modal?
                                                                           :contact     contact
                                                                           :transaction (assoc @tx-atom
                                                                                               :amount (money/formatted->internal
                                                                                                        (money/bignumber input-amount)
                                                                                                        (:symbol coin)
                                                                                                        (:decimals coin)))}])}
                   [react/text {:style {:color       (if disabled? colors/white colors/blue)
                                        :font-size   15
                                        :line-height 22}}
                    (i18n/label :t/next)]])]]]))]))))

(defview choose-amount-token []
  (letsubs [{:keys [transaction modal? contact native-currency]} [:get-screen-params :wallet-choose-amount]
            balance       [:balance]
            prices        [:prices]
            network       [:account/network]
            all-tokens    [:wallet/all-tokens]
            fiat-currency [:wallet/currency]]
    [choose-amount-token-helper {:balance         balance
                                 :network         network
                                 :all-tokens      all-tokens
                                 :modal?          modal?
                                 :prices          prices
                                 :native-currency native-currency
                                 :fiat-currency   fiat-currency
                                 :contact         contact
                                 :transaction     transaction}]))

;; ----------------------------------------------------------------------
;; Step 3 Final Overview
;; ----------------------------------------------------------------------

;; TODOS
;; look at duplicate logic and create a model so that we can simply execute methods against that model
;; instead of piecing together various information for each individual computation

(def signing-popup
  {:background-color        colors/white
   :border-top-left-radius  8
   :border-top-right-radius 8
   :position                :absolute
   :left                    0
   :right                   0
   :bottom                  0})

(defn confirm-modal [signing? {:keys [transaction total-amount gas-amount native-currency fiat-currency total-fiat]}]
  [react/view {:style signing-popup}
   [react/text {:style {:color       colors/black
                        :font-size   15
                        :line-height 22
                        :margin-top  23
                        :text-align  :center}}
    (i18n/label :t/total)]
   [react/text {:style {:color       colors/black
                        :margin-top  4
                        :font-weight :bold
                        :font-size   22
                        :line-height 28
                        :text-align  :center}}
    (str total-amount " " (name (:symbol transaction)))]
   (when-not (= :ETH (:symbol transaction))
     [react/text {:style {:color       colors/black
                          :margin-top  13
                          :font-weight :bold
                          :font-size   22
                          :line-height 28
                          :text-align  :center}}
      (str gas-amount " " (name (:symbol native-currency)))])
   [react/text {:style {:color       colors/gray
                        :text-align  :center
                        :margin-top  3
                        :line-height 21
                        :font-size   15}}
    (str "~ " (:symbol fiat-currency "$") total-fiat)]
   [react/view {:style {:flex-direction  :row
                        :justify-content :center
                        :padding-top     16
                        :padding-bottom  24}}
    [react/touchable-highlight
     {:on-press #(reset! signing? true)
      :style    {:padding-horizontal 39
                 :padding-vertical   12
                 :border-radius      8
                 :background-color   colors/blue-light}}
     [react/text {:style {:font-size   15
                          :line-height 22
                          :color       colors/blue}}
      (i18n/label :t/confirm)]]]])

(defn- phrase-word [word]
  [react/text {:style {:color       colors/blue
                       :font-size   15
                       :line-height 22
                       :font-weight "500"
                       :width       "33%"
                       :text-align  :center}}
   word])

(defn- phrase-separator []
  [react/view {:style {:height           "100%"
                       :width            1
                       :background-color colors/gray-light}}])

(defview sign-modal [account {:keys [transaction contact total-amount gas-amount native-currency fiat-currency
                                     total-fiat all-tokens chain flow]}]
  (letsubs [password     (reagent/atom nil)
            in-progress? (reagent/atom nil)]
    (let [phrase (string/split (:signing-phrase account) #" ")]
      [react/view {:style {:position :absolute
                           :left     0
                           :right    0
                           :bottom   0}}
       [tooltip/tooltip (i18n/label :t/wallet-passphrase-reminder)
        {:bottom-value 12
         :color        colors/blue
         :text-color   colors/white}]
       [react/view {:style {:background-color        colors/white
                            :border-top-left-radius  8
                            :border-top-right-radius 8}}
        [react/view {:flex              1
                     :height            46
                     :margin-top        18
                     :flex-direction    :row
                     :align-items       :center
                     :margin-horizontal "15%"
                     :border-width      1
                     :border-color      colors/gray-light
                     :border-radius     218}
         [phrase-word (first phrase)]
         [phrase-separator]
         [phrase-word (second phrase)]
         [phrase-separator]
         [phrase-word (last phrase)]]
        [react/text {:style {:color       colors/black
                             :margin-top  13
                             :font-weight :bold
                             :font-size   22
                             :line-height 28
                             :text-align  :center}}
         (str "Send" " " total-amount " " (name (:symbol transaction)))]
        (when-not (= :ETH (:symbol transaction))
          [react/text {:style {:color       colors/black
                               :margin-top  13
                               :font-weight :bold
                               :font-size   22
                               :line-height 28
                               :text-align  :center}}
           (str "Send" " " gas-amount " " (name (:symbol native-currency)))])
        [react/text {:style {:color       colors/gray
                             :text-align  :center
                             :margin-top  3
                             :line-height 21
                             :font-size   15}}
         (str "~ " (:symbol fiat-currency "$") total-fiat)]
        [react/text-input
         {:auto-focus             true
          :secure-text-entry      true
          :placeholder            (i18n/label :t/enter-your-login-password)
          :placeholder-text-color colors/gray
          :on-change-text         #(reset! password %)
          :style                  {:flex              1
                                   :margin-top        15
                                   :margin-horizontal 15
                                   :padding           14
                                   :padding-bottom    18
                                   :background-color  colors/gray-lighter
                                   :border-radius     8
                                   :font-size         15
                                   :letter-spacing    -0.2
                                   :height            52}
          :accessibility-label    :enter-password-input
          :auto-capitalize        :none}]
        [react/view {:style {:flex-direction  :row
                             :justify-content :center
                             :padding-top     16
                             :padding-bottom  24}}
         [react/touchable-highlight
          {:on-press #(events/send-transaction-wrapper {:transaction  transaction
                                                        :password     @password
                                                        :flow         flow
                                                        :all-tokens   all-tokens
                                                        :in-progress? in-progress?
                                                        :chain        chain
                                                        :contact      contact
                                                        :account      account})
           :disabled @in-progress?
           :style    {:padding-horizontal 39
                      :padding-vertical   12
                      :border-radius      8
                      :background-color   colors/blue-light}}
          [react/text {:style {:font-size   15
                               :line-height 22
                               :color       colors/blue}}
           (i18n/label :t/send)]]]]])))

(defview confirm-and-sign [params]
  (letsubs [signing? (reagent/atom false)
            account  [:account/account]]
    (if-not @signing?
      [confirm-modal signing? params]
      [sign-modal account params])))

(defn transaction-overview [{:keys [flow transaction contact token native-currency
                                    fiat-currency prices all-tokens chain]}]
  (let [tx-atom                (reagent/atom transaction)
        network-fees-modal-ref (atom nil)
        open-network-fees!     #(anim-ref-send @network-fees-modal-ref :open!)
        close-network-fees!    #(anim-ref-send @network-fees-modal-ref :close!)
        modal?                 (= :dapp flow)]
    (when-not (optimal-gas-present? transaction)
      (refresh-optimal-gas (some :symbol [transaction native-currency]) tx-atom))
    (fn []
      (let [transaction @tx-atom
            gas-gas-price->fiat
            (fn [gas-map]
              (network-fees prices token fiat-currency (max-fee gas-map)))

            network-fee-eth (max-fee (current-gas transaction))
            network-fee-fiat
            (when (optimal-gas-present? transaction)
              (network-fees prices token fiat-currency network-fee-eth))

            formatted-amount
            (money/internal->formatted (:amount transaction)
                                       (:symbol token)
                                       (:decimals token))
            amount-str (str formatted-amount
                            " " (wallet.utils/display-symbol token))

            fiat-amount (some-> (token->fiat-conversion prices token fiat-currency formatted-amount)
                                (money/with-precision 2))

            total-amount (some-> (if (= :ETH (:symbol transaction))
                                   (.add (money/bignumber formatted-amount) network-fee-eth)
                                   (money/bignumber formatted-amount))
                                 (money/with-precision (:decimals token)))
            gas-amount (some-> (when-not (= :ETH (:symbol transaction))
                                 (money/bignumber network-fee-eth))
                               (money/with-precision 18))

            total-fiat (some-> (token->fiat-conversion prices token fiat-currency total-amount)
                               (money/with-precision 2))]
        [wallet.components/simple-screen {:avoid-keyboard? (not modal?)
                                          :status-bar-type (if modal? :modal-wallet :wallet)}
         [toolbar flow (i18n/label :t/send-amount) (:public-key contact)]
         [react/view {:style {:flex             1
                              :border-top-width 1
                              :border-top-color colors/white-light-transparent}}
          (when (optimal-gas-present? @tx-atom)
            [slide-up-modal {:anim-ref       #(reset! network-fees-modal-ref %)
                             :swipe-dismiss? true}
             [custom-gas-input-panel
              (-> (select-keys @tx-atom [:gas :gas-price :optimal-gas :optimal-gas-price])
                  (assoc
                   :fiat-currency fiat-currency
                   :gas-gas-price->fiat gas-gas-price->fiat
                   :on-submit (fn [{:keys [gas gas-price]}]
                                (when (and gas gas-price)
                                  (swap! tx-atom assoc :gas gas :gas-price gas-price))
                                (close-network-fees!))))]])
          [react/text {:style {:margin-top 18
                               :text-align :center
                               :font-size  15
                               :color      colors/white-transparent}}
           (i18n/label :t/recipient)]
          [react/view
           (when contact
             [react/view {:style {:margin-top      10
                                  :flex-direction  :row
                                  :justify-content :center}}
              [photos/photo (:photo-path contact) {:size list.styles/image-size}]])
           [react/text {:style {:color             colors/white
                                :margin-horizontal 24
                                :margin-top        10
                                :line-height       22
                                :font-size         15
                                :text-align        :center}}
            (ethereum/normalized-address (:to transaction))]]
          [react/text {:style {:margin-top 18
                               :font-size  15
                               :text-align :center
                               :color      colors/white-transparent}}
           (i18n/label :t/amount)]
          [react/view {:style {:flex-direction    :row
                               :align-items       :center
                               :margin-top        10
                               :margin-horizontal 24}}
           [react/text {:style {:color     colors/white
                                :font-size 15}} (i18n/label :t/sending)]
           [react/view {:style {:flex 1}}
            [react/text {:style {:color       colors/white
                                 :line-height 21
                                 :font-size   15
                                 :font-weight "500"
                                 :text-align  :right}}
             amount-str]
            [react/text {:style {:color       colors/white-transparent
                                 :line-height 21
                                 :font-size   15
                                 :text-align  :right}}
             (str "~ " (:symbol fiat-currency "$")  fiat-amount " " (:code fiat-currency))]]]
          [react/view {:style {:margin-horizontal 24
                               :margin-top        10
                               :padding-top       10
                               :border-top-width  1
                               :border-top-color  colors/white-light-transparent}}
           [react/view {:style {:flex-direction :row
                                :align-items    :center}}
            [react/touchable-highlight {:on-press #(open-network-fees!)
                                        :style    {:background-color   colors/black-transparent
                                                   :padding-horizontal 16
                                                   :padding-vertical   9
                                                   :border-radius      8}}
             [react/view {:style {:flex-direction :row
                                  :align-items    :center}}
              [react/text {:style {:color         colors/white
                                   :padding-right 10
                                   :font-size     15}} (i18n/label :t/network-fee)]
              [vector-icons/icon :icons/settings {:color colors/white}]]]
            [react/view {:style {:flex 1}}
             [react/text {:style {:color       colors/white
                                  :line-height 21
                                  :font-size   15
                                  :font-weight "500"
                                  :text-align  :right}}
              (str network-fee-eth " " (wallet.utils/display-symbol native-currency))]
             [react/text {:style {:color       colors/white-transparent
                                  :line-height 21
                                  :font-size   15
                                  :text-align  :right}}
              (str "~ "  network-fee-fiat " " (:code fiat-currency))]]]]
          [static-modal
           [confirm-and-sign {:transaction     transaction
                              :contact         contact
                              :total-amount    total-amount
                              :gas-amount      gas-amount
                              :native-currency native-currency
                              :fiat-currency   fiat-currency
                              :total-fiat      total-fiat
                              :all-tokens      all-tokens
                              :chain           chain
                              :flow            flow}]]]]))))

(defview txn-overview []
  (letsubs [{:keys [transaction flow contact]} [:get-screen-params :wallet-txn-overview]
            prices                             [:prices]
            network                            [:account/network]
            all-tokens                         [:wallet/all-tokens]
            fiat-currency                      [:wallet/currency]]
    (let [chain           (ethereum/network->chain-keyword network)
          native-currency (tokens/native-currency chain)
          token           (tokens/asset-for all-tokens
                                            (ethereum/network->chain-keyword network) (:symbol transaction))]
      [transaction-overview {:transaction     transaction
                             :flow            flow
                             :contact         contact
                             :prices          prices
                             :network         network
                             :token           token
                             :native-currency native-currency
                             :fiat-currency   fiat-currency
                             :all-tokens      all-tokens
                             :chain           chain}])))

;; MAIN SEND TRANSACTION VIEW
(defn- send-transaction-view [_opts]
  (reagent/create-class
   {:reagent-render (fn [opts] [choose-address-contact opts])}))

;; SEND TRANSACTION FROM WALLET (CHAT)
(defview send-transaction []
  (letsubs [transaction    [:wallet.send/transaction]
            network        [:account/network]
            network-status [:network-status]
            all-tokens     [:wallet/all-tokens]
            contacts       [:contacts/all-added-people-contacts]]
    [send-transaction-view {:modal?         false
                            :transaction    (dissoc transaction :gas :gas-price)
                            :network        network
                            :all-tokens     all-tokens
                            :contacts       contacts
                            :network-status network-status}]))

;; SEND TRANSACTION FROM DAPP
(defview send-transaction-modal []
  (letsubs [{:keys [transaction flow contact]} [:get-screen-params :wallet-send-transaction-modal]
            prices                             [:prices]
            network                            [:account/network]
            all-tokens                         [:wallet/all-tokens]
            fiat-currency                      [:wallet/currency]]
    (let [chain           (ethereum/network->chain-keyword network)
          native-currency (tokens/native-currency chain)
          token           (tokens/asset-for all-tokens
                                            (ethereum/network->chain-keyword network) (:symbol transaction))]
      [transaction-overview {:transaction     transaction
                             :flow            flow
                             :contact         contact
                             :prices          prices
                             :network         network
                             :token           token
                             :native-currency native-currency
                             :fiat-currency   fiat-currency
                             :all-tokens      all-tokens
                             :chain           chain}])))
