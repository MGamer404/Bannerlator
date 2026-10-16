/*
 * Link probe for libbannerin_guest.a: references every function of the C ABI so the whole
 * library is pulled into a shared object linked the way the libfakeinput.so builds link it.
 * ci/build.sh then checks that object's dynamic dependencies and exports. Valid C and C++.
 */
#include "bannerin.h"

#include <fcntl.h>

#ifdef __cplusplus
extern "C" {
#endif

__attribute__((visibility("default"))) long bannerin_linkprobe_entry(int slot);

static void probe_log(int level, const char *line)
{
    (void)level;
    (void)line;
}

long bannerin_linkprobe_entry(int slot)
{
    bannerin_handle h = 0;
    struct bannerin_state st;
    struct bannerin_stats gs;
    struct bannerin_slot_stats ss;
    unsigned char buf[BANNERIN_EVENT_SIZE * 4];
    long acc = 0;
    int fd;

    bannerin_set_logger(probe_log);
    acc += bannerin_init();
    fd = bannerin_attach(slot, O_RDONLY | O_NONBLOCK | O_CLOEXEC, &h);
    if (fd >= 0) {
        acc += bannerin_fd(h) + bannerin_slot(h) + bannerin_is_removed(h) + bannerin_has_pending(h);
        acc += bannerin_poll_revents(h, 1);
        acc += (long)bannerin_read(h, buf, sizeof buf, 1);
        acc += bannerin_current_state(h, &st);
        acc += bannerin_detach(h);
    }
    acc += bannerin_get_stats(&gs, sizeof gs);
    acc += bannerin_slot_stats(slot, &ss, sizeof ss);
    acc += (long)bannerin_api_version();
    acc += (long)bannerin_axis_hz() + bannerin_axis_auto();
    bannerin_set_logger(0);
    return acc;
}

#ifdef __cplusplus
}
#endif
