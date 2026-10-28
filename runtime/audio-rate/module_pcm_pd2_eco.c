/* PD2 optional 22.05 kHz PCM, derived from D2Android/Winlator (LGPL-2.1).
 * The original runtime PCM is never replaced. Only an explicit Eco launch
 * selects this separately named module through its private ALSA config.
 */
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static char *pd2_eco_getenv(const char *name)
{
    if (strcmp(name, "D2_ALSA_RATE") == 0) return "22050";
    return getenv(name);
}

#define getenv pd2_eco_getenv
#define android_aserver_d2 pd2_eco
#include "vendor/module_pcm_android_aserver_d2.c"
#undef android_aserver_d2
#undef getenv
