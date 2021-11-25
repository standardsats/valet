mv local.properties{,_bp}
podman run -v $PWD:/app/simplebitcoinwallet/wallet:z sbw
mv local.properties{_bp,}
