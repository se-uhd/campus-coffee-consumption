import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AccountingService } from './accounting.service';
import { GlobalActivityEntryDto } from '../models';

describe('AccountingService', () => {
  let service: AccountingService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AccountingService, provideHttpClient(withXhr()), provideHttpClientTesting()]
    });
    service = TestBed.inject(AccountingService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the global activity feed with the limit and offset params', async () => {
    const expected: GlobalActivityEntryDto[] = [];
    const promise = service.allActivity(25, 50);
    const req = httpMock.expectOne((r) => r.url === '/api/users/activity');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('limit')).toBe('25');
    expect(req.request.params.get('offset')).toBe('50');
    req.flush(expected);
    expect(await promise).toEqual(expected);
  });

  it('GETs the activity CSV as a blob', async () => {
    const blob = new Blob(['x'], { type: 'text/csv' });
    const promise = service.activityCsvBlob();
    const req = httpMock.expectOne('/api/users/activity.csv');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(blob);
    expect(await promise).toBe(blob);
  });
});
