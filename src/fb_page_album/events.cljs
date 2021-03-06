(ns fb-page-album.events
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [re-frame.core :as rf]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<! >! chan]]
            [clojure.pprint :refer [pprint]]))

(rf/reg-event-db              ;; sets up initial application state
  :initialize                 ;; usage:  (dispatch [:initialize])
  (fn [_ _]
    {:active-panel :home}))

(comment
  (reset! re-frame.db/app-db {})
  (rf/subscribe [:api/get-access-token]))

(rf/reg-event-db
  :api/set-access-token
  (fn [db [_ token]]
    (assoc db :api/access-token token)))

(def fb-graph-url "https://graph.facebook.com/")

(defn gen-url [id]
  (str fb-graph-url id))

(defn page-url [id]
  (gen-url id))
(defn page-album-url [id]
  (str (page-url id) "/albums"))

(defn album-url [id]
  (gen-url id))

(defn likes [resource-url]
  (str resource-url "/likes?summary=true"))

(def album-req
  {:fields "description,is_user_facing,location,place,type,name,link,photo_count,video_count"})

(defn photo-url [album-id]
  (str fb-graph-url album-id "/photos"))
(def photo-req
  {:fields "event,link,picture,place,target,updated_time"})

(defn fetch-page [page-id after]
  (let [albums (chan)
        access-token (rf/subscribe [:api/get-access-token])]
    (go (let [response (<! (http/get (page-album-url page-id)
                                     {:with-credentials? false
                                      :query-params {"access_token" @access-token
                                                     "limit" 50
                                                     "after" after}}))  ;; MAX is 50
                                                    ;; TODO: Implement paging
                                                    ;;"after" "MTM5NjY4NjU4NzEwMzQzNQZDZD"}}))
              res-status (:status response)
              body (:body response)]
          (prn :status res-status)
          (>! albums body)))
    albums))


(defn fetch-album [{:keys [id name] :as album}]
  (let [album-data (chan)
        access-token (rf/subscribe [:api/get-access-token])]
    (go (let [response (<! (http/get (album-url id)
                                     {:with-credentials? false
                                      :query-params {"access_token" @access-token
                                                     "fields" "photos.limit(1){images}"}}))
              res-status (:status response)
              body (:body response)
              img (get-in body [:photos :data 0 :images 0 :source])]
          ;(prn :status res-status)
          (prn id name :image img)
          (>! album-data (assoc album :cover img))))
    album-data))

(comment
  (fetch-album {:id "508561589343676"
                :name "who take a picture"}))

(defn fetch-album-likes [{:keys [id name] :as album}]
  (let [album-data (chan)
        access-token (rf/subscribe [:api/get-access-token])]
    (go (let [response (<! (http/get (likes (album-url id))
                                     {:with-credentials? false
                                      :query-params {"access_token" @access-token}}))
              res-status (:status response)
              body (:body response)
              likes (get-in body [:summary :total_count])]
          ;(prn :status res-status)
          (prn id name :likes likes)
          (>! album-data (assoc album :likes likes))))
    album-data))

(rf/reg-fx
  :page/fetch-albums
  (fn [{:keys [page-id on-success on-failed]}]
    ;; TODO: Implement paging loop
    (go
      (on-success (<! (fetch-page page-id nil))))))

(comment
  (rf/dispatch [:page/get-albums "IRoamAlone"])
  (rf/dispatch [:page/get-albums "bnk48unclefan"]))
(rf/reg-event-fx
  :page/get-albums
  (fn [db [_ page-id]]
    (prn :get-albums page-id)
    {:page/fetch-albums {:page-id page-id
                         :on-success #(rf/dispatch [:page/set-albums page-id %])}}))

(rf/reg-event-fx
  :page/set-albums
  (fn [{:keys [db]} [_ page-id albums]]
    {:db (assoc-in db [page-id :albums] albums)
     :albums/fetch-cover {:albums (:data albums)}
     :albums/fetch-likes {:albums (:data albums)}}))

(rf/reg-fx
  :albums/fetch-cover
  (fn [{:keys [albums]}]
    (doseq [a albums
            :let [id (:id a)]]
      (go (rf/dispatch [:album/set-cover  (<! (fetch-album a))])))))

(rf/reg-fx
  :albums/fetch-likes
  (fn [{:keys [albums]}]
    (doseq [a albums
            :let [id (:id a)]]
      (go (rf/dispatch [:album/set-album  (<! (fetch-album-likes a))])))))

(rf/reg-event-fx
  :album/set-album
  (fn [{:keys [db]} [_ album]]
    (let [id (:id album)
          name (:name album)
          likes (:likes album)]
      {:db (update-in db [:albums id] #(assoc % :id id :name name :likes likes))})))

(rf/reg-event-fx
  :album/set-likes
  (fn [{:keys [db]} [_ album]]
    (let [id (:id album)
          name (:name album)
          likes (:likes album)]
      {:db (assoc-in db [:albums id :likes] likes)})))

(rf/reg-event-fx
  :album/set-cover
  (fn [{:keys [db]} [_ album]]
    (let [id (:id album)
          name (:name album)
          cover (:cover album)]
      {:db (assoc-in db [:albums id :cover] cover)})))

(comment
  (->> (get @re-frame.db/app-db :albums)
       (map second)
       (sort-by :likes)))

;; ROUTES

(rf/reg-event-db
 :routes/set-active-panel
 (fn [db [_ panel]]
   (assoc db :active-panel panel)))
