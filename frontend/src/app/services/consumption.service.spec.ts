import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ConsumptionService } from './consumption.service';
import { ConsumptionDto } from '../models';

describe('ConsumptionService', () => {
  let service: ConsumptionService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ConsumptionService, provideHttpClient(withXhr()), provideHttpClientTesting()]
    });
    service = TestBed.inject(ConsumptionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs a member consumption by id with the limit and offset params', async () => {
    const expected: ConsumptionDto = { total: 3, changes: [] };
    const promise = service.getForUser('user-1', 5, 0);
    const req = httpMock.expectOne((r) => r.url === '/api/users/user-1/consumption');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('limit')).toBe('5');
    expect(req.request.params.get('offset')).toBe('0');
    req.flush(expected);
    expect(await promise).toEqual(expected);
  });

  it('POSTs a +1 delta to apply a single-step change for a member', async () => {
    const expected: ConsumptionDto = { total: 4, changes: [] };
    const promise = service.changeForUser('user-1', 1);
    const req = httpMock.expectOne('/api/users/user-1/consumption');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ delta: 1 });
    req.flush(expected);
    expect(await promise).toEqual(expected);
  });

  it('PUTs an absolute override with a note for an admin', async () => {
    const expected: ConsumptionDto = { total: 0, changes: [] };
    const promise = service.overrideForUser('user-1', 0, 'Paid');
    const req = httpMock.expectOne('/api/users/user-1/consumption');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ total: 0, note: 'Paid' });
    req.flush(expected);
    expect(await promise).toEqual(expected);
  });

  it('PUTs an override with an empty note as undefined so it records null', async () => {
    const expected: ConsumptionDto = { total: 2, changes: [] };
    const promise = service.overrideForUser('user-1', 2, '');
    const req = httpMock.expectOne('/api/users/user-1/consumption');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ total: 2, note: undefined });
    req.flush(expected);
    expect(await promise).toEqual(expected);
  });
});
