(use-modules (gnu)
             (gnu packages)
             (gnu system)
             (guix profiles)
             (gnu packages gcc)
             (gnu packages java))

(concatenate-manifests
 (list (packages->manifest
        (append (list (list gcc "lib")
                      (list openjdk11 "jdk"))
                %base-packages))
       (specifications->manifest
        (list "unzip"
              "curl"
              "pulseaudio"
              "nss-certs"
              "sdkmanager"
              "maven"))))
