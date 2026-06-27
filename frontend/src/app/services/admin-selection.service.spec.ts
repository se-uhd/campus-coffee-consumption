import { TestBed } from '@angular/core/testing';
import { AdminSelectionService } from './admin-selection.service';

describe('AdminSelectionService', () => {
  let service: AdminSelectionService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [AdminSelectionService] });
    service = TestBed.inject(AdminSelectionService);
  });

  it('selectFromParam selects the user named by the param and returns it', () => {
    service.setOwnUserId('admin-1');
    expect(service.selectFromParam('member-7')).toBe('member-7');
    expect(service.selectedUserId()).toBe('member-7');
    expect(service.isOwnAccountSelected()).toBe(false);
  });

  it('selectFromParam falls back to the admin own account when the param is absent', () => {
    service.setOwnUserId('admin-1');
    service.select('member-7');
    expect(service.selectFromParam(null)).toBe('admin-1');
    expect(service.selectedUserId()).toBe('admin-1');
    expect(service.isOwnAccountSelected()).toBe(true);
  });

  it('selectFromParam treats an empty param the same as an absent one (own account)', () => {
    service.setOwnUserId('admin-1');
    expect(service.selectFromParam('')).toBe('admin-1');
    expect(service.selectedUserId()).toBe('admin-1');
  });

  it('ownUserId returns the recorded own id and empty before it is set', () => {
    expect(service.ownUserId).toBe('');
    service.setOwnUserId('admin-1');
    expect(service.ownUserId).toBe('admin-1');
  });

  it('reset clears the selection and the own id so nothing leaks into the next session', () => {
    service.setOwnUserId('admin-1');
    service.select('member-7');
    service.reset();
    expect(service.selectedUserId()).toBe('');
    expect(service.ownUserId).toBe('');
    // a later selectFromParam(null) no longer falls back to the previous admin's own id
    expect(service.selectFromParam(null)).toBe('');
  });
});
