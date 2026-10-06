#include <assert.h>
#include <stdint.h>
#include <stdio.h>
typedef int NTSTATUS;
typedef unsigned char KIRQL;
#define STATUS_CANCELLED ((int)0xc0000120)
#define STATUS_PENDING 0x103
struct entry {
  struct entry *next, *prev;
};
struct hid_queue {
  int lock;
  struct entry irp_queue;
};
typedef struct IRP {
  int Cancel;
  void (*cancelRoutine)(void);
  struct {
    struct {
      struct entry ListEntry;
    } Overlay;
  } Tail;
  struct {
    NTSTATUS Status;
  } IoStatus;
  int pending;
} IRP;
static int claimed, scenario, exchanges, locked;
static void read_cancel_routine(void) {}
static void KeAcquireSpinLock(int *p, KIRQL *irql) {
  (void)p;
  *irql = 0;
  assert(!locked);
  locked = 1;
}
static void KeReleaseSpinLock(int *p, KIRQL irql) {
  (void)p;
  (void)irql;
  assert(locked);
  locked = 0;
}
static void InitializeListHead(struct entry *p) { p->next = p->prev = p; }
static void InsertTailList(struct entry *head, struct entry *p) {
  p->next = head;
  p->prev = head->prev;
  head->prev->next = p;
  head->prev = p;
}
static void IoMarkIrpPending(IRP *i) { i->pending = 1; }
static void (*IoSetCancelRoutine(IRP *i, void (*routine)(void)))(void) {
  void (*old)(void) = i->cancelRoutine;
  i->cancelRoutine = routine;
  exchanges++;
  if (exchanges == 1 && scenario == 2) {
    i->Cancel = 1;
    claimed = 1;
    i->cancelRoutine = 0;
  }
  return old;
}
/* PD2_SOURCE_FUNCTIONS */
static void check(int fixed, int which) {
  struct hid_queue q = {0};
  IRP i = {0};
  InitializeListHead(&q.irp_queue);
  InitializeListHead(&i.Tail.Overlay.ListEntry);
  scenario = which;
  claimed = exchanges = locked = 0;
  i.Cancel = (which == 1);
  NTSTATUS ret =
      fixed ? fixed_hid_queue_push_irp(&q, &i) : hid_queue_push_irp(&q, &i);
  if (which == 0) {
    assert(ret == STATUS_PENDING && i.cancelRoutine == read_cancel_routine &&
           i.pending);
  }
  if (which == 1) {
    if (fixed) {
      assert(ret == STATUS_CANCELLED && !claimed && !i.pending);
    } else {
      assert(ret == STATUS_PENDING && !claimed && !i.cancelRoutine &&
             i.pending);
    }
  }
  if (which == 2) {
    if (fixed) {
      assert(ret == STATUS_PENDING && claimed && i.pending);
    } else {
      assert(ret == STATUS_CANCELLED && claimed && !i.pending);
    }
  }
  printf("%s scenario %d: status %08x, cancel callback owner %d, queued %d\n",
         fixed ? "fixed" : "original", which, (uint32_t)ret, claimed,
         i.pending);
}
int main(void) {
  for (int f = 0; f < 2; f++)
    for (int s = 0; s < 3; s++)
      check(f, s);
  return 0;
}
