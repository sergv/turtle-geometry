
;; date:   Tuesday, 25 June 2013
;; author: Sergey Vinokurov
;; email:  serg.foo@gmail.com

(ns org.turtle.geometry.utils
  (:import [org.turtle.geometry.R]))

(defmacro ^int resource
  ([id] `(resource :id ~id))
  ([resource-type resource-name]
     `(. ~(case resource-type
            :drawable org.turtle.geometry.R$drawable
            :id       org.turtle.geometry.R$id
            :layout   org.turtle.geometry.R$layout
            :menu     org.turtle.geometry.R$menu
            :string   org.turtle.geometry.R$string
            (throw (java.lang.RuntimeException
                    (str "invalid resource type: " resource-type))))
         ~(symbol (name resource-name)))))


