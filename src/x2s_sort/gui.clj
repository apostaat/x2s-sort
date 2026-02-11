(ns x2s-sort.gui
  (:require [x2s-sort.rename :as rename])
  (:import (javax.swing JFrame JPanel JButton JLabel JTextField JCheckBox JTextArea JScrollPane JFileChooser SwingUtilities UIManager BorderFactory)
           (java.awt BorderLayout GridBagLayout GridBagConstraints Insets Font Color)
           (java.awt.event ActionListener)))

(defn- run-on-edt
  [f]
  (if (SwingUtilities/isEventDispatchThread)
    (f)
    (SwingUtilities/invokeLater f)))

(defn- append-log!
  [^JTextArea log ^String line]
  (run-on-edt
   (fn []
     (.append log (str line "\n"))
     (.setCaretPosition log (.getLength (.getDocument log))))))

(defn- set-enabled!
  [^JButton btn enabled?]
  (run-on-edt
   (fn []
     (.setEnabled btn (boolean enabled?)))))

(defn- set-status!
  [^JLabel label ^String text]
  (run-on-edt
   (fn []
     (.setText label text))))

(def ^:private theme
  {:bg (Color. 18 22 28)
   :panel (Color. 24 28 36)
   :fg (Color. 220 255 235)
   :accent (Color. 0 210 120)
   :muted (Color. 130 160 150)})

(defn- apply-theme!
  [components]
  (let [font (Font. Font/MONOSPACED Font/PLAIN 12)
        bold (Font. Font/MONOSPACED Font/BOLD 12)]
    (doseq [c components]
      (doto c
        (.setFont font)
        (.setForeground (:fg theme))))
    {:font font :bold bold}))

(defn- choose-dir
  [^JFrame frame]
  (let [chooser (doto (JFileChooser.)
                  (.setFileSelectionMode JFileChooser/DIRECTORIES_ONLY)
                  (.setDialogTitle "Select folder for renaming"))
        result (.showOpenDialog chooser frame)]
    (when (= result JFileChooser/APPROVE_OPTION)
      (.. chooser getSelectedFile getAbsolutePath))))

(defn- start-rename!
  [root dry-run? reorder? log status run-btn]
  (future
    (try
      (set-enabled! run-btn false)
      (set-status! status "Running...")
      (append-log! log (str "Folder: " root))
      (let [{:keys [operations reordered-dirs]} (rename/rename-tree! root {:dry-run? dry-run? :reorder? reorder?})]
        (if (seq operations)
          (do
            (append-log! log (if dry-run? "Planned renames:" "Renamed:"))
            (doseq [{:keys [from to]} operations]
              (append-log! log (str from " -> " to)))
            (append-log! log (str "Total: " (count operations))))
          (if reorder?
            (append-log! log "No renames.")
            (append-log! log "Nothing to rename.")))
        (when reorder?
          (append-log! log (str "Reordered directories: " reordered-dirs))))
      (set-status! status "Done.")
      (catch Exception e
        (append-log! log (str "Error: " (.getMessage e)))
        (when-let [data (ex-data e)]
          (append-log! log (str "Details: " data)))
        (set-status! status "Failed."))
      (finally
        (set-enabled! run-btn true)))))

(defn launch!
  []
  (run-on-edt
   (fn []
     (try
       (UIManager/setLookAndFeel (UIManager/getSystemLookAndFeelClassName))
       (catch Throwable _))
     (let [frame (JFrame. "X2S Sort")
           panel (JPanel. (GridBagLayout.))
           c (GridBagConstraints.)
           path-label (JLabel. "Folder")
           path-field (doto (JTextField. 20)
                        (.setEditable false))
           browse-btn (JButton. "Browse")
           dry-run (JCheckBox. "Dry run (no changes)")
           reorder (JCheckBox. "Recreate order (players ignore sort)")
           run-btn (JButton. "Run")
           status (JLabel. "Ready")
           log (doto (JTextArea. 6 28)
                 (.setEditable false)
                 (.setFont (Font. Font/MONOSPACED Font/PLAIN 12)))
           scroll (JScrollPane. log)]
       (apply-theme! [path-label path-field browse-btn dry-run reorder run-btn status log])
       (set! (.insets c) (Insets. 4 4 4 4))
       (set! (.anchor c) GridBagConstraints/WEST)

       (.setBackground panel (:panel theme))
       (.setOpaque panel true)
       (.setBackground frame (:bg theme))

       (.setBackground log (:bg theme))
       (.setBackground (.getViewport scroll) (:bg theme))
       (.setCaretColor log (:accent theme))
       (.setBorder scroll (BorderFactory/createLineBorder (:accent theme) 1))

       (doseq [cb [dry-run reorder]]
         (.setOpaque cb true)
         (.setBackground cb (:panel theme)))

       (doseq [btn [browse-btn run-btn]]
         (.setFocusPainted btn false)
         (.setBackground btn (:panel theme))
         (.setForeground btn (:accent theme))
         (.setBorder btn (BorderFactory/createLineBorder (:accent theme) 1)))

       (.setBackground path-field (:bg theme))
       (.setBorder path-field (BorderFactory/createLineBorder (:accent theme) 1))
       (.setForeground status (:muted theme))
       (.setForeground path-label (:accent theme))

       (set! (.gridx c) 0) (set! (.gridy c) 0) (set! (.weightx c) 0.0)
       (.add panel path-label c)
       (set! (.gridx c) 1) (set! (.gridy c) 0) (set! (.weightx c) 1.0)
       (set! (.fill c) GridBagConstraints/HORIZONTAL)
       (.add panel path-field c)
       (set! (.gridx c) 2) (set! (.gridy c) 0) (set! (.weightx c) 0.0)
       (set! (.fill c) GridBagConstraints/NONE)
       (.add panel browse-btn c)

       (set! (.gridx c) 1) (set! (.gridy c) 1) (set! (.gridwidth c) 2)
       (.add panel dry-run c)
       (set! (.gridx c) 1) (set! (.gridy c) 2)
       (.add panel reorder c)
       (set! (.gridx c) 1) (set! (.gridy c) 3)
       (.add panel run-btn c)
       (set! (.gridx c) 1) (set! (.gridy c) 4)
       (.add panel status c)

       (.add frame panel BorderLayout/NORTH)
       (.add frame scroll BorderLayout/CENTER)

       (.addActionListener browse-btn
                           (reify ActionListener
                             (actionPerformed [_ _]
                               (when-let [dir (choose-dir frame)]
                                 (.setText path-field dir)))))

       (.addActionListener run-btn
                           (reify ActionListener
                             (actionPerformed [_ _]
                               (let [root (.getText path-field)]
                                 (if (or (nil? root) (empty? root))
                                   (append-log! log "Please select a folder first.")
                                   (start-rename! root (.isSelected dry-run) (.isSelected reorder) log status run-btn))))))

       (doto frame
         (.setDefaultCloseOperation JFrame/EXIT_ON_CLOSE)
         (.pack)
         (.setLocationRelativeTo nil)
         (.setVisible true))))))
