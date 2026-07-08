FROM quay.io/centos/centos:stream10
RUN dnf update -y \
    && dnf install -y epel-release \
    && dnf install -y nss-tools \
    && dnf install -y chromium \
    && dnf install -y google-noto-sans-fonts google-noto-sans-mono-fonts \
                      google-noto-sans-symbols2-fonts google-noto-color-emoji-fonts \
                      liberation-fonts \
    && dnf clean -y all
RUN mkdir -p /root/.pki/nssdb \
    && certutil -d /root/.pki/nssdb -N --empty-password
ENV DISPLAY=:0
# Set this with root certificate file paths. Use comma to specify more than one file.
ENV CA_CERTS=""
SHELL ["/bin/bash", "-c"]
CMD IFS=','; for cert in $CA_CERTS; do certutil -d sql:/root/.pki/nssdb -A -t "C,," -n "$(basename "$cert" .pem | sed 's/.crt$//')" -i "$cert" && cp /certs/rootCA.crt /etc/pki/ca-trust/source/anchors/; done && update-ca-trust && chromium-browser --no-sandbox --disable-setuid-sandbox --disable-dev-shm-usage --disable-renderer-backgrounding --use-gl=angle --use-angle=swiftshader --disable-features=DBus,VaapiVideoDecoder