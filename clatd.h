/*
 * Copyright 2011 Daniel Drown
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * clatd.h - main routines used by clatd
 */
#ifndef __CLATD_H__
#define __CLATD_H__

#include <signal.h>
#include <stdlib.h>
#include <sys/uio.h>

struct tun_data;

#define MAXMTU 65536
#define PACKETLEN (MAXMTU + sizeof(struct tun_pi))
#define CLATD_VERSION "1.5"

#define ARRAY_SIZE(x) (sizeof(x) / sizeof((x)[0]))

// how frequently (in seconds) to poll for an address change while traffic is passing
#define INTERFACE_POLL_FREQUENCY 30

// how frequently (in seconds) to poll for an address change while there is no traffic
#define NO_TRAFFIC_INTERFACE_POLL_FREQUENCY 90

extern volatile sig_atomic_t running;

int ipv6_address_changed(const char *interface);
void event_loop(struct tun_data *tunnel);

/* function: parse_int
 * parses a string as a decimal/hex/octal signed integer
 *   str - the string to parse
 *   out - the signed integer to write to, gets clobbered on failure
 */
static inline int parse_int(const char *str, int *out) {
  char *end_ptr;
  *out = strtol(str, &end_ptr, 0);
  return *str && !*end_ptr;
}

/* function: parse_unsigned
 * parses a string as a decimal/hex/octal unsigned integer
 *   str - the string to parse
 *   out - the unsigned integer to write to, gets clobbered on failure
 */
static inline int parse_unsigned(const char *str, unsigned *out) {
  char *end_ptr;
  *out = strtoul(str, &end_ptr, 0);
  return *str && !*end_ptr;
}

#endif /* __CLATD_H__ */
