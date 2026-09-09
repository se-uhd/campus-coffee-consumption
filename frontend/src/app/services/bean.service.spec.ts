import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BeanService } from './bean.service';
import { CoffeeBeanDto } from '../models';

function bean(id: string, name: string): CoffeeBeanDto {
  return { id, name } as CoffeeBeanDto;
}

/** Flushes every `/api/beans` request currently pending, letting the queue drain between rounds. */
async function flushCatalog(httpMock: HttpTestingController, catalog: CoffeeBeanDto[]): Promise<number> {
  let flushed = 0;
  for (let round = 0; round < 10; round++) {
    const pending = httpMock.match('/api/beans');
    pending.forEach((request) => request.flush(catalog));
    flushed += pending.length;
    await new Promise((tick) => setTimeout(tick, 0));
  }
  return flushed;
}

describe('BeanService', () => {
  let service: BeanService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [BeanService, provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(BeanService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('reports a rename as done once the catalog reflects it', async () => {
    const renamed = service.rename('bean-1', 'Kenya AB');
    httpMock.expectOne('/api/beans/bean-1').flush(bean('bean-1', 'Kenya AB'));
    await flushCatalog(httpMock, [bean('bean-1', 'Kenya AB')]);

    await expect(renamed).resolves.toEqual(bean('bean-1', 'Kenya AB'));
    expect(service.selectable()).toEqual([bean('bean-1', 'Kenya AB')]);
  });

  it('does not report a committed rename as failed when only the catalog re-read fails', async () => {
    // The write has already committed by the time the catalog is re-read, so surfacing that read's failure
    // would tell the admin their rename failed when it did not, and invite them to repeat it.
    const renamed = service.rename('bean-1', 'Kenya AB');
    httpMock.expectOne('/api/beans/bean-1').flush(bean('bean-1', 'Kenya AB'));
    for (let round = 0; round < 10; round++) {
      httpMock.match('/api/beans').forEach((request) => request.error(new ProgressEvent('offline')));
      await new Promise((tick) => setTimeout(tick, 0));
    }

    await expect(renamed).resolves.toEqual(bean('bean-1', 'Kenya AB'));
  });

  it('does not report a committed merge as failed when only the catalog re-read fails', async () => {
    const merged = service.merge('bean-1', 'bean-2');
    httpMock.expectOne('/api/beans/bean-1/merge').flush(bean('bean-1', 'Kenya AA'));
    for (let round = 0; round < 10; round++) {
      httpMock.match('/api/beans').forEach((request) => request.error(new ProgressEvent('offline')));
      await new Promise((tick) => setTimeout(tick, 0));
    }

    await expect(merged).resolves.toEqual(bean('bean-1', 'Kenya AA'));
  });

  it('retries a catalog read that failed rather than remembering the bean as done', async () => {
    // ensureContains refreshes at most once per bean id, so that a bean the catalog will never hold (a merge
    // tombstone the rating prompt still names) costs one read and not one per call. That bound must be
    // recorded only for a read that actually happened: a failed one has to stay retryable, or a bean stays
    // missing from the rating dropdown for the rest of the session.
    const warm = service.ensureLoaded();
    await flushCatalog(httpMock, [bean('bean-1', 'Kenya AA')]);
    await warm;

    const firstAttempt = service.ensureContains('bean-9');
    for (let round = 0; round < 10; round++) {
      httpMock.match('/api/beans').forEach((request) => request.error(new ProgressEvent('offline')));
      await new Promise((tick) => setTimeout(tick, 0));
    }
    await firstAttempt.catch(() => undefined);

    // A second attempt must therefore still go to the network, and still report a second failure. If the
    // first attempt had recorded the bean as done, this one would return early and resolve instead, which
    // is indistinguishable from success to the caller.
    const secondAttempt = service.ensureContains('bean-9');
    for (let round = 0; round < 10; round++) {
      httpMock.match('/api/beans').forEach((request) => request.error(new ProgressEvent('offline')));
      await new Promise((tick) => setTimeout(tick, 0));
    }

    await expect(
      secondAttempt,
      'a failed catalog read must be retried, not remembered as done'
    ).rejects.toBeDefined();
  });
});
