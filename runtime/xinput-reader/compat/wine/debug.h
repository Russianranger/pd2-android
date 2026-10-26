/* Build adapter only: bounded runtime telemetry is in reader_lifecycle.h. */
#ifndef PD2_WINE_DEBUG_COMPAT_H
#define PD2_WINE_DEBUG_COMPAT_H
#define WINE_DEFAULT_DEBUG_CHANNEL(name)
#define TRACE(...) ((void)0)
#define ERR(...) ((void)0)
#endif
