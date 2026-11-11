// Привязка исходящих сокетов процесса к физическому интерфейсу (мимо VPN).
// Подгружается в RSP2PServer через DYLD_INSERT_LIBRARIES. Интерфейс — из RL_BIND_IF (по умолчанию en0).
// Трогаем только соединения/пакеты НЕ на loopback: локальный API службы (127.0.0.1) работает как раньше.
#include <dlfcn.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <stdio.h>

#define DYLD_INTERPOSE(_new, _old) \
  __attribute__((used)) static struct { const void *n; const void *o; } _interpose_##_old \
  __attribute__((section("__DATA,__interpose"))) = { (const void *)(unsigned long)&_new, (const void *)(unsigned long)&_old };

static unsigned int ifidx(void) {
  static unsigned int idx = 0;
  if (!idx) {
    const char *n = getenv("RL_BIND_IF");
    idx = if_nametoindex(n && *n ? n : "en0");
  }
  return idx;
}

static int is_external(const struct sockaddr *sa) {
  if (!sa) return 0;
  if (sa->sa_family == AF_INET) {
    unsigned long a = ntohl(((const struct sockaddr_in *)sa)->sin_addr.s_addr);
    return (a >> 24) != 127 && a != 0;
  }
  if (sa->sa_family == AF_INET6) {
    const struct in6_addr *a = &((const struct sockaddr_in6 *)sa)->sin6_addr;
    if (IN6_IS_ADDR_LOOPBACK(a) || IN6_IS_ADDR_UNSPECIFIED(a)) return 0;
    if (IN6_IS_ADDR_V4MAPPED(a)) return a->s6_addr[12] != 127;
    return 1;
  }
  return 0;
}

static void bind_if(int fd, const struct sockaddr *sa) {
  unsigned int idx = ifidx();
  if (!idx || !is_external(sa)) return;
  if (sa->sa_family == AF_INET6) setsockopt(fd, IPPROTO_IPV6, IPV6_BOUND_IF, &idx, sizeof idx);
  else setsockopt(fd, IPPROTO_IP, IP_BOUND_IF, &idx, sizeof idx);
}

int my_connect(int fd, const struct sockaddr *sa, socklen_t len) {
  bind_if(fd, sa);
  return connect(fd, sa, len);
}
ssize_t my_sendto(int fd, const void *buf, size_t n, int fl, const struct sockaddr *sa, socklen_t len) {
  bind_if(fd, sa);
  return sendto(fd, buf, n, fl, sa, len);
}
ssize_t my_sendmsg(int fd, const struct msghdr *m, int fl) {
  if (m) bind_if(fd, (const struct sockaddr *)m->msg_name);
  return sendmsg(fd, m, fl);
}
// --- DNS мимо VPN: Incy (FakeIP) отдаёт 240.0.0.x/198.18.x, напрямую по ним не уйти.
// Если система вернула такой адрес — спрашиваем имя у RL_DNS (по умолчанию 77.88.8.8) через тот же интерфейс.
#include <arpa/inet.h>
#include <netdb.h>
#include <pthread.h>
#include <sys/time.h>
#include <unistd.h>
#include <time.h>

static int is_fake(unsigned long a) {   // a — в порядке хоста
  return (a >> 28) == 0xF /* 240.0.0.0/4 */ || (a >> 17) == (0xC612 >> 1) /* 198.18.0.0/15 */;
}

static pthread_mutex_t dns_mx = PTHREAD_MUTEX_INITIALIZER;
static char cache_name[16][256]; static char cache_ip[16][16]; static int cache_n = 0;

static int dns_a(const char *name, char *out_ip) {   // 1 — нашли A-запись
  pthread_mutex_lock(&dns_mx);
  for (int i = 0; i < cache_n; i++) if (!strcmp(cache_name[i], name)) { strcpy(out_ip, cache_ip[i]); pthread_mutex_unlock(&dns_mx); return 1; }
  pthread_mutex_unlock(&dns_mx);

  unsigned char q[512]; int n = 0;
  unsigned short id = (unsigned short)(getpid() ^ time(NULL));
  q[n++] = id >> 8; q[n++] = id & 0xff; q[n++] = 1; q[n++] = 0; q[n++] = 0; q[n++] = 1;
  memset(q + n, 0, 6); n += 6;
  const char *p = name;
  while (*p) {
    const char *d = strchr(p, '.'); size_t l = d ? (size_t)(d - p) : strlen(p);
    if (l == 0 || l > 63 || n + l + 6 > sizeof q) return 0;
    q[n++] = (unsigned char)l; memcpy(q + n, p, l); n += l; p += l; if (*p == '.') p++;
  }
  q[n++] = 0; q[n++] = 0; q[n++] = 1; q[n++] = 0; q[n++] = 1;

  const char *srv = getenv("RL_DNS"); if (!srv || !*srv) srv = "77.88.8.8";
  struct sockaddr_in sa = {0}; sa.sin_family = AF_INET; sa.sin_port = htons(53); inet_pton(AF_INET, srv, &sa.sin_addr);
  int fd = socket(AF_INET, SOCK_DGRAM, 0); if (fd < 0) return 0;
  unsigned int idx = ifidx(); if (idx) setsockopt(fd, IPPROTO_IP, IP_BOUND_IF, &idx, sizeof idx);
  struct timeval tv = {3, 0}; setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
  unsigned char r[1500]; ssize_t m = -1;
  for (int attempt = 0; attempt < 2 && m < 12; attempt++) {
    sendto(fd, q, n, 0, (struct sockaddr *)&sa, sizeof sa);   // настоящий sendto: fd уже привязан
    m = recv(fd, r, sizeof r, 0);
  }
  close(fd);
  if (m < 12 || r[0] != q[0] || r[1] != q[1]) return 0;
  int an = (r[6] << 8) | r[7], off = n;   // ответ начинается копией вопроса
  for (int i = 0; i < an && off + 12 <= m; i++) {
    if ((r[off] & 0xC0) == 0xC0) off += 2; else { while (off < m && r[off]) off += r[off] + 1; off++; }
    if (off + 10 > m) break;
    int type = (r[off] << 8) | r[off + 1], rdl = (r[off + 8] << 8) | r[off + 9]; off += 10;
    if (type == 1 && rdl == 4 && off + 4 <= m) {
      inet_ntop(AF_INET, r + off, out_ip, 16);
      pthread_mutex_lock(&dns_mx);
      if (cache_n < 16) { strncpy(cache_name[cache_n], name, 255); strcpy(cache_ip[cache_n], out_ip); cache_n++; }
      pthread_mutex_unlock(&dns_mx);
      return 1;
    }
    off += rdl;
  }
  return 0;
}

int my_getaddrinfo(const char *node, const char *svc, const struct addrinfo *h, struct addrinfo **res) {
  int rc = getaddrinfo(node, svc, h, res);
  if (rc != 0 || !node || !*res) return rc;
  for (struct addrinfo *a = *res; a; a = a->ai_next) {
    if (a->ai_family == AF_INET && is_fake(ntohl(((struct sockaddr_in *)a->ai_addr)->sin_addr.s_addr))) {
      char ip[16];
      if (dns_a(node, ip)) {
        freeaddrinfo(*res);
        struct addrinfo hh = h ? *h : (struct addrinfo){0};
        hh.ai_flags |= AI_NUMERICHOST;
        if (hh.ai_family == AF_UNSPEC || hh.ai_family == AF_INET6) hh.ai_family = AF_INET;
        return getaddrinfo(ip, svc, &hh, res);
      }
      break;
    }
  }
  return rc;
}

struct hostent *my_gethostbyname(const char *name) {
  struct hostent *he = gethostbyname(name);
  if (he && he->h_addrtype == AF_INET && he->h_addr_list[0] &&
      is_fake(ntohl(*(unsigned int *)he->h_addr_list[0]))) {
    char ip[16];
    if (dns_a(name, ip)) return gethostbyname(ip);
  }
  return he;
}

DYLD_INTERPOSE(my_connect, connect)
DYLD_INTERPOSE(my_sendto, sendto)
DYLD_INTERPOSE(my_sendmsg, sendmsg)
DYLD_INTERPOSE(my_getaddrinfo, getaddrinfo)
DYLD_INTERPOSE(my_gethostbyname, gethostbyname)
