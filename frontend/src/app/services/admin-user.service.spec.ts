import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { AdminUserService } from './admin-user.service';
import { UserService } from './user.service';
import { AccountingService } from './accounting.service';
import { UserBalanceDto, UserDto } from '../models';

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason?: unknown) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** Flushes pending microtasks (one macrotask turn) so awaited continuations run before an assertion. */
const tick = () => new Promise<void>((r) => setTimeout(r));

function userDto(loginName: string, over: Partial<UserDto> = {}): UserDto {
  return {
    id: loginName,
    loginName,
    emailAddress: `${loginName}@x.test`,
    firstName: 'First',
    lastName: 'Last',
    role: 'USER',
    active: true,
    ...over
  };
}

function balance(userId: string, count: number, balanceCents: number): UserBalanceDto {
  return { userId, loginName: userId, firstName: 'First', lastName: 'Last', count, balanceCents };
}

describe('AdminUserService', () => {
  let service: AdminUserService;
  let userList: Mock<() => Promise<UserDto[]>>;
  let overview: Mock<() => Promise<UserBalanceDto[]>>;

  beforeEach(() => {
    userList = vi.fn<() => Promise<UserDto[]>>();
    overview = vi.fn<() => Promise<UserBalanceDto[]>>();
    TestBed.configureTestingModule({
      providers: [
        AdminUserService,
        { provide: UserService, useValue: { list: userList } },
        { provide: AccountingService, useValue: { overview } }
      ]
    });
    service = TestBed.inject(AdminUserService);
  });

  it('on the first visit awaits the load, then caches the merged rows', async () => {
    const users = deferred<UserDto[]>();
    const balances = deferred<UserBalanceDto[]>();
    userList.mockReturnValueOnce(users.promise);
    overview.mockReturnValueOnce(balances.promise);

    expect(service.rows()).toBeNull();
    let resolved = false;
    const done = service.ensureLoaded().then(() => {
      resolved = true;
    });

    await tick();
    expect(resolved).toBe(false); // the first visit waits for the data
    expect(service.rows()).toBeNull();

    users.resolve([userDto('u1', { active: true })]);
    balances.resolve([balance('u1', 2, -100)]);
    await done;

    expect(resolved).toBe(true);
    expect(service.rows()).toEqual([
      expect.objectContaining({ loginName: 'u1', active: true, count: 2, balanceCents: -100 })
    ]);
  });

  it('when reopened returns the cached rows immediately and revalidates in the background', async () => {
    userList.mockResolvedValueOnce([userDto('u1', { active: true })]);
    overview.mockResolvedValueOnce([balance('u1', 1, 0)]);
    await service.ensureLoaded();
    expect(userList).toHaveBeenCalledTimes(1);

    const users2 = deferred<UserDto[]>();
    const balances2 = deferred<UserBalanceDto[]>();
    userList.mockReturnValueOnce(users2.promise);
    overview.mockReturnValueOnce(balances2.promise);

    await service.ensureLoaded(); // returns without awaiting the new fetch
    expect(service.rows()![0].active).toBe(true); // still the cached value
    expect(userList).toHaveBeenCalledTimes(2); // background revalidation issued

    users2.resolve([userDto('u1', { active: false })]);
    balances2.resolve([balance('u1', 1, 0)]);
    await tick();
    expect(service.rows()![0].active).toBe(false);
  });

  it('reload awaits the in-flight read, then refetches, so a just-mutated row is not overwritten', async () => {
    userList.mockResolvedValueOnce([userDto('u1', { active: true })]);
    overview.mockResolvedValueOnce([balance('u1', 3, -150)]);
    await service.ensureLoaded();
    expect(service.rows()![0].active).toBe(true);

    // an in-flight read started before the write; it will return the STALE active:true
    const staleUsers = deferred<UserDto[]>();
    const staleBalances = deferred<UserBalanceDto[]>();
    userList.mockReturnValueOnce(staleUsers.promise);
    overview.mockReturnValueOnce(staleBalances.promise);
    const inFlight = service.load();

    // the admin's write: the next (fresh) fetch must see active:false
    const freshUsers = deferred<UserDto[]>();
    const freshBalances = deferred<UserBalanceDto[]>();
    userList.mockReturnValueOnce(freshUsers.promise);
    overview.mockReturnValueOnce(freshBalances.promise);

    const reloaded = service.reload();
    expect(userList).toHaveBeenCalledTimes(2); // reload is awaiting the in-flight read, not refetching yet

    staleUsers.resolve([userDto('u1', { active: true })]);
    staleBalances.resolve([balance('u1', 3, -150)]);
    await inFlight;
    await tick();
    expect(service.rows()![0].active).toBe(true); // the stale snap-back (would flicker without reload)
    expect(userList).toHaveBeenCalledTimes(3); // only now does reload issue its refetch

    freshUsers.resolve([userDto('u1', { active: false })]);
    freshBalances.resolve([balance('u1', 3, -150)]);
    await reloaded;
    expect(service.rows()![0].active).toBe(false); // the write survives
  });

  it('a failed revalidation sets the error flag and keeps the previously loaded rows', async () => {
    userList.mockResolvedValueOnce([userDto('u1', { active: true })]);
    overview.mockResolvedValueOnce([balance('u1', 1, 0)]);
    await service.ensureLoaded();
    expect(service.loadError()).toBe(false);

    userList.mockRejectedValueOnce(new Error('network'));
    overview.mockResolvedValueOnce([balance('u1', 1, 0)]);
    await service.reload();

    expect(service.loadError()).toBe(true);
    expect(service.rows()![0].active).toBe(true); // prior rows preserved
  });

  it('a failed first load sets the error flag and leaves the rows null', async () => {
    userList.mockRejectedValueOnce(new Error('network'));
    overview.mockResolvedValueOnce([]);
    await service.ensureLoaded();
    expect(service.loadError()).toBe(true);
    expect(service.rows()).toBeNull();
  });

  it('a successful reload after an error clears the error flag', async () => {
    userList.mockRejectedValueOnce(new Error('network'));
    overview.mockResolvedValueOnce([]);
    await service.ensureLoaded();
    expect(service.loadError()).toBe(true);

    userList.mockResolvedValueOnce([userDto('u1', { active: true })]);
    overview.mockResolvedValueOnce([balance('u1', 1, 0)]);
    await service.reload();
    expect(service.loadError()).toBe(false);
  });

  it('load coalesces concurrent callers into a single fetch', async () => {
    const users = deferred<UserDto[]>();
    const balances = deferred<UserBalanceDto[]>();
    userList.mockReturnValueOnce(users.promise);
    overview.mockReturnValueOnce(balances.promise);

    const a = service.load();
    const b = service.load();
    expect(userList).toHaveBeenCalledTimes(1);
    expect(overview).toHaveBeenCalledTimes(1);

    users.resolve([userDto('u1', { active: true })]);
    balances.resolve([balance('u1', 1, 0)]);
    await Promise.all([a, b]);
    expect(userList).toHaveBeenCalledTimes(1);
  });

  it('merges rows, skipping a user with a null id and defaulting a missing balance to zero', async () => {
    userList.mockResolvedValueOnce([userDto('u1', { active: true }), userDto('ghost', { id: null })]);
    overview.mockResolvedValueOnce([]); // no balance entries
    await service.ensureLoaded();

    expect(service.rows()).toHaveLength(1);
    expect(service.rows()![0]).toEqual(
      expect.objectContaining({ loginName: 'u1', count: 0, balanceCents: 0, role: 'USER' })
    );
  });
});
