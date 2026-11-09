# Caddyfile baked into the image — single-file bind mounts misbehave on
# the NAS filesystem (solved the same way on every other stack).
FROM caddy:2
COPY Caddyfile /etc/caddy/Caddyfile
