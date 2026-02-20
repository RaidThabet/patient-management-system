FROM quay.io/keycloak/keycloak:26.3.3

ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start-dev"]
