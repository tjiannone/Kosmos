#!/bin/bash
num_regex='^[0-9]+$'
function GetRandomPort() {
	local __resultvar=$1
	if ! [ "$INSTALLED_LSOF" == true ]; then
		echo "Generating random port please wait..."
		if [[ $distro =~ "CentOS" ]]; then
			yum -y -q install lsof
		elif [[ $distro =~ "Ubuntu" ]] || [[ $distro =~ "Debian" ]] || [[ $distro =~ "Raspbian" ]]; then
			apt-get -y install lsof >/dev/null
		fi
		local RETURN_CODE
		RETURN_CODE=$?
		if [ $RETURN_CODE -ne 0 ]; then
			PrintWarning "lsof package did not installed successfully. The randomized port may be in use."
		else
			INSTALLED_LSOF=true
		fi
	fi
	local PORTL=$((RANDOM % 16383 + 49152))
	if lsof -Pi :$PORTL -sTCP:LISTEN -t >/dev/null; then
		GetRandomPort __resultvar
	fi
	eval "$__resultvar"="$PORTL"
}
function PrintWarning(){
	echo "$(tput setaf 3)Warning!$(tput sgr 0) $1"
}
function RunCloakAdmin(){
	ck-client -s 127.0.0.1 -p $PORT -a "$(jq -r '.AdminUID' ckserver.json)" -l "$LOCAL_PANEL_PORT" -c ckadminclient.json & #The & will make this to run in background
	echo "Please wait 5 seconds to let the ck-client start..."
	sleep 5 # you can change this number if you like
}
function GenerateProxyBook() {
	#Format of the proxy book is arr[method] = "(t/d)ip:port"
	PROXY_BOOK=""
	for method in "${!proxyBook[@]}"; do
		PROXY_BOOK+='"'
		PROXY_BOOK+=$method
		PROXY_BOOK+='":["'
		s=${proxyBook[$method]}
		if [[ ${s:0:1} == "t" ]]; then #At first check the TCP
			PROXY_BOOK+='tcp","'
		else #UDP
			PROXY_BOOK+='udp","'
		fi
		PROXY_BOOK+=${s:1}
		PROXY_BOOK+='"] , '
	done
	PROXY_BOOK=${PROXY_BOOK::${#PROXY_BOOK}-2}
}
function WriteClientFile() {
	content=$(curl -L http://checkip.amazonaws.com)
	echo "{
 	\"Public IP\":\"$content\",
 	\"Password\":\"$Password\",
  	\"Port\":\"$PORT\",
   	\"Encryption\":\"$cipher\",
    	\"Cloak Proxy Method\":\"shadowsocks\",
	\"ProxyMethod\":\"$ckmethod\",
	\"EncryptionMethod\":\"$ckcrypt\",
	\"UID\":\"$ckbuid\",
	\"PublicKey\":\"$ckpub\",
	\"ServerName\":\"$ckwebaddr\",
	\"NumConn\":4,
	\"BrowserSig\":\"chrome\",
	\"StreamTimeout\": 300
}" >"$ckclient_name.json"
}
function ListAllUIDs() {
	#At first list all of the unrestricted users
	mapfile -t UIDS < <(jq -r '.BypassUID[]' ckserver.json)
	#Remove the UID used for admin panel
	for value in "${UIDS[@]}"; do
		[[ $value != "$ckaauid" ]] && new_array+=("$value")
	done
	UIDS=("${new_array[@]}")
	unset new_array
	#Now list all of the restricted users
	GetRandomPort LOCAL_PANEL_PORT
	RunCloakAdmin
	RESTRICTED_UIDS=$(curl http://127.0.0.1:$LOCAL_PANEL_PORT/admin/users -sS)
	kill $!
	wait $! 2>/dev/null
	mapfile -t UIDS_2 < <(jq -r '.[].UID?' <<<"$RESTRICTED_UIDS")
	UIDS=("${UIDS[@]}" "${UIDS_2[@]}") #Merge them
}
function ShowConnectionInfo() {
	echo "Your Server IP: $PUBLIC_IP"
	echo "Password:       $Password"
	echo "Port:           $PORT"
	echo "Encryption:     $cipher"
	echo "Cloak Proxy Method:   shadowsocks"
	echo "Cloak UID:            $ckuid"
	echo "Cloak Public Key:     $ckpub"
	echo "Cloak Encryption:     plain"
	echo "Cloak Server Name:    Domain or ip of RedirAddr (Default bing.com)"
	echo "Cloak NumConn:        4 or more"
	echo "Cloak MaskBrowser:    firefox or chrome"
	echo "Cloak StreamTimeout:	300"
	echo "Also read more about these arguments at https://github.com/cbeuw/Cloak#client"
	echo
	echo "Download cloak client for android from https://github.com/cbeuw/Cloak-android/releases"
	echo "Download cloak client for PC from https://github.com/cbeuw/Cloak/releases"
	echo
	echo
	echo
	ckpub=$(echo "$ckpub" | sed -r 's/=/\\=/g')
	ckuid=$(echo "$ckuid" | sed -r 's/=/\\=/g')
	SERVER_BASE64=$(printf "%s" "$cipher:$Password" | base64)
	SERVER_CLOAK_ARGS="ck-client;UID=$ckuid;PublicKey=$ckpub;ServerName=bing.com;BrowserSig=chrome;NumConn=4;ProxyMethod=shadowsocks;EncryptionMethod=plain;StreamTimeout=300"
	SERVER_CLOAK_ARGS=$(printf "%s" "$SERVER_CLOAK_ARGS" | curl -Gso /dev/null -w %{url_effective} --data-urlencode @- "" | cut -c 3-) #https://stackoverflow.com/a/10797966/4213397
	SERVER_BASE64="ss://$SERVER_BASE64@$PUBLIC_IP:$PORT?plugin=$SERVER_CLOAK_ARGS"
	qrencode -t ansiutf8 "$SERVER_BASE64"
	echo
	echo
	echo "Or just use this string: $SERVER_BASE64"
}
function GetArch(){
	arch="amd64"
}
function DownloadAndInstallSSRust() {
	# Convert the arch
	local SS_ARCH
	if [[ "$arch" == "386" ]]; then
		SS_ARCH="i686-unknown-linux-musl"
	elif [[ "$arch" == "amd64" ]]; then
		if [[ $distro =~ "CentOS" ]]; then # Centos uses glibc 2.17 which is very old
			SS_ARCH="x86_64-unknown-linux-musl"
		else
			SS_ARCH="x86_64-unknown-linux-gnu"
		fi
	elif [[ "$arch" == "arm" ]]; then
		SS_ARCH="arm-unknown-linux-musleabi"
	elif [[ "$arch" == "arm64" ]]; then
		SS_ARCH="aarch64-unknown-linux-gnu"
	fi
	# Generate the download link
	url=$(wget -O - -o /dev/null https://api.github.com/repos/shadowsocks/shadowsocks-rust/releases/latest | grep -E "/shadowsocks-v.+.$SS_ARCH.tar.xz\"" | grep -P 'https(.*)[^"]' -o)
	wget -O shadowsocks.tar.xz "$url"
	tar xf shadowsocks.tar.xz -C /usr/bin/
	rm shadowsocks.tar.xz
	# Create the config
	mkdir /etc/shadowsocks-rust
	echo "{
    \"server\":\"127.0.0.1\",
    \"server_port\":$SS_PORT,
    \"password\":\"$Password\",
    \"timeout\":60,
    \"method\":\"$cipher\",
    \"ipv6_first\":true,
    \"dns\":\"$ss_dns\"
}" >/etc/shadowsocks-rust/config.json
	# Setup the service
	echo "[Unit]
Description=Shadowsocks-Rust Server Service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
Group=root
LimitNOFILE=32768
ExecStart=/usr/bin/ssserver -c config.json
WorkingDirectory=/etc/shadowsocks-rust

[Install]
WantedBy=multi-user.target" >/etc/systemd/system/shadowsocks-rust-server.service
	systemctl daemon-reload
	systemctl restart shadowsocks-rust-server
	systemctl enable shadowsocks-rust-server
}
function DownloadCloak() {
	url=$(wget -O - -o /dev/null https://api.github.com/repos/cbeuw/Cloak/releases/latest | grep "/ck-server-linux-$arch-" | grep -P 'https(.*)[^"]' -o)
	wget -O ck-server "$url"
	chmod +x ck-server
	mv ck-server /usr/bin
	url=$(wget -O - -o /dev/null https://api.github.com/repos/cbeuw/Cloak/releases/latest | grep "/ck-client-linux-$arch-" | grep -P 'https(.*)[^"]' -o)
	wget -O ck-client "$url"
	chmod +x ck-client
	mv ck-client /usr/bin
}
if [[ "$EUID" -ne 0 ]]; then #Check root
	echo "Please run this script as root"
	exit 1
fi
distro=$(awk -F= '/^NAME/{print $2}' /etc/os-release)

clear
echo "Cloak installer by Hirbod Behnam"
echo "Cloak at https://github.com/cbeuw/Cloak"
echo "Source at https://github.com/HirbodBehnam/Shadowsocks-Cloak-Installer"
echo "Shadowsocks-rust at https://github.com/shadowsocks/shadowsocks-rust"
echo
echo
#Get port
PORT=443
echo $PORT
ckwebaddr="204.79.197.200:443"
#Check arch
GetArch
declare -A proxyBook
#Setup shadowsocks itself
SHADOWSOCKS=true
Password=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w 16 | head -n 1) #https://gist.github.com/earthgecko/3089509
echo "$Password was chosen."
cipher="chacha20-ietf-poly1305"
ss_dns="8.8.8.8"
GetRandomPort SS_PORT
proxyBook+=(["shadowsocks"]="t127.0.0.1:$SS_PORT")
if [[ ${#proxyBook[@]} == 0 ]]; then
	echo "Cannot forward nothing. Please at least choose one rule."
	exit 1
fi
#Install some stuff
if [[ $distro =~ "CentOS" ]]; then
	yum -y install epel-release
	yum -y install wget jq curl
else
	apt-get update
	apt-get -y install wget jq curl
fi
#Install cloak
DownloadCloak
#Ok lets talk about this:
Local_Address_Book_For_Admin="panel"
#This is a id created for proxy book to make local admin connection though this script. Also the forwarding address will be 127.0.0.1:0; This port does not exist so it points out to nowhere and can be only used for admin panel
proxyBook+=(["$Local_Address_Book_For_Admin"]="t127.0.0.1:0")
GenerateProxyBook #Generate json style proxy book
#Download and install Cloak
mkdir /etc/cloak
cd /etc/cloak || exit 1
ckauid=$(ck-server -u)
ckaauid=$(ck-server -u) #This is only used by this script for admin panel
ckbuid=$(ck-server -u)
IFS=, read ckpub ckpv <<<$(ck-server -k)
echo "{
  \"ProxyBook\": {
    $PROXY_BOOK
  },
  \"BypassUID\": [
    \"$ckaauid\",
    \"$ckbuid\"
  ],
  \"BindAddr\":[\":$PORT\"],
  \"RedirAddr\": \"$ckwebaddr\",
  \"PrivateKey\": \"$ckpv\",
  \"AdminUID\": \"$ckauid\",
  \"DatabasePath\": \"userinfo.db\",
  \"StreamTimeout\": 300
}" >>ckserver.json
echo "PORT=$PORT
ckaauid=\"$ckaauid\"" >>ckport.txt
echo "{
	\"ProxyMethod\":\"$Local_Address_Book_For_Admin\",
	\"EncryptionMethod\":\"plain\",
	\"UID\":\"$ckaauid\",
	\"PublicKey\":\"$ckpub\",
	\"ServerName\":\"www.bing.com\",
	\"NumConn\":1,
	\"BrowserSig\":\"chrome\",
	\"StreamTimeout\": 300
}" >>ckadminclient.json
ckcrypt="aes-128-gcm" #Play it safe; Why 128 bit? Most of the traffic in sites are encrypted with 128 bit key
for ckmethod in "${!proxyBook[@]}"; do
	if [[ "$ckmethod" == "$Local_Address_Book_For_Admin" ]]; then
		continue
	fi
	ckclient_name=$ckmethod
	if [[ "$ckmethod" == "shadowsocks" ]]; then
		ckcrypt="plain"
		WriteClientFile
		ckcrypt="aes-128-gcm"
	else
		WriteClientFile
	fi
done
#Create service for Cloak
echo "[Unit]
Description=Cloak Server Service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
Group=root
LimitNOFILE=32768
ExecStart=/usr/bin/ck-server -c ckserver.json
WorkingDirectory=/etc/cloak

[Install]
WantedBy=multi-user.target" >/etc/systemd/system/cloak-server.service
systemctl daemon-reload
systemctl start cloak-server
systemctl enable cloak-server
#setup firewall
if [[ $distro =~ "CentOS" ]]; then
	SETFIREWALL=true
	if ! yum -q list installed firewalld &>/dev/null; then
		echo
		read -r -p "Looks like \"firewalld\" is not installed Do you want to install it?(y/n) " -e -i "y" OPTION
		OPTION="$(echo $OPTION | tr '[A-Z]' '[a-z]')"
		case $OPTION in
		"y" | "Y")
			yum -y install firewalld
			systemctl enable firewalld
			systemctl start firewalld
			;;
		*)
			SETFIREWALL=false
			;;
		esac
	fi
	if [ "$SETFIREWALL" = true ]; then
		firewall-cmd --zone=public --add-port="$PORT"/tcp
		firewall-cmd --runtime-to-permanent
	fi
elif [[ $distro =~ "Ubuntu" ]]; then
	if dpkg --get-selections | grep -q "^ufw[[:space:]]*install$" >/dev/null; then
		ufw allow "$PORT"/tcp
	else
		echo
		read -r -p "Looks like \"UFW\"(Firewall) is not installed Do you want to install it?(y/n) " -e -i "y" OPTION
		case $OPTION in
		"y" | "Y")
			apt-get install ufw
			ufw allow ssh
			ufw allow "$PORT"/tcp
			;;
		esac
	fi
	#Use BBR on user will
	if ! [ "$(sysctl -n net.ipv4.tcp_congestion_control)" = "bbr" ]; then
		echo 'net.core.default_qdisc=fq' | tee -a /etc/sysctl.conf
		echo 'net.ipv4.tcp_congestion_control=bbr' | tee -a /etc/sysctl.conf
		sysctl -p
	fi
elif [[ $distro =~ "Debian" ]] || [[ $distro =~ "Raspbian" ]]; then
	apt-get -y install iptables-persistent iptables
	iptables -A INPUT -p tcp --dport "$PORT" --jump ACCEPT
	iptables-save >/etc/iptables/rules.v4
fi
#Install and setup shadowsocks
if [[ "$SHADOWSOCKS" == true ]]; then
	# Install aftermath dependecies
	if [[ $distro =~ "CentOS" ]]; then
		yum -y install haveged qrencode
	elif [[ $distro =~ "Ubuntu" ]] || [[ $distro =~ "Debian" ]] || [[ $distro =~ "Raspbian" ]]; then
		apt-get -y install haveged qrencode
	fi
	# Setup shadowsocks
	DownloadAndInstallSSRust
	#Show keys server and...
	PUBLIC_IP="$(curl https://api.ipify.org -sS)"
	CURL_EXIT_STATUS=$?
	if [ $CURL_EXIT_STATUS -ne 0 ]; then
		PUBLIC_IP="YOUR_IP"
	fi
	clear
	ckuid="$ckbuid"
	ShowConnectionInfo
fi
echo "Some sample client files with no restrictions are available at /etc/cloak"
echo "Installing Done!"
