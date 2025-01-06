package org.upyog.adv.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.validation.Valid;

import org.apache.commons.lang.StringUtils;
import org.egov.common.contract.request.RequestInfo;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.upyog.adv.config.BookingConfiguration;
import org.upyog.adv.constants.BookingConstants;
import org.upyog.adv.enums.BookingStatusEnum;
import org.upyog.adv.repository.BookingRepository;
import org.upyog.adv.repository.querybuilder.AdvertisementBookingQueryBuilder;
import org.upyog.adv.service.ADVEncryptionService;
import org.upyog.adv.service.BookingService;
import org.upyog.adv.service.DemandService;
import org.upyog.adv.service.EnrichmentService;
import org.upyog.adv.service.PaymentTimerService;
import org.upyog.adv.util.BookingUtil;
import org.upyog.adv.util.MdmsUtil;
import org.upyog.adv.validator.BookingValidator;
import org.upyog.adv.web.models.AdvertisementSearchCriteria;
import org.upyog.adv.web.models.AdvertisementSlotAvailabilityDetail;
import org.upyog.adv.web.models.AdvertisementSlotSearchCriteria;
import org.upyog.adv.web.models.ApplicantDetail;
import org.upyog.adv.web.models.BookingDetail;
import org.upyog.adv.web.models.BookingRequest;

import digit.models.coremodels.PaymentDetail;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class BookingServiceImpl implements BookingService {

	@Autowired
	private MdmsUtil mdmsUtil;

	@Autowired
	@Lazy
	private BookingRepository bookingRepository;
	@Autowired
	private BookingValidator bookingValidator;

	@Autowired
	private EnrichmentService enrichmentService;

	@Autowired
	private DemandService demandService;

	@Autowired
	private BookingConfiguration config;

	@Autowired
	private PaymentTimerService paymentTimerService;

	@Autowired
	private ADVEncryptionService encryptionService;

	@Override
	public BookingDetail createBooking(@Valid BookingRequest bookingRequest) {
		log.info("Create advertisement booking for user : " + bookingRequest.getRequestInfo().getUserInfo().getId());
		String uuid = bookingRequest.getRequestInfo().getUserInfo().getUuid();
		// TODO move to util calss 
		String tenantId = bookingRequest.getBookingApplication().getTenantId().split("\\.")[0];
		if (bookingRequest.getBookingApplication().getTenantId().split("\\.").length == 1) {
			throw new CustomException(BookingConstants.INVALID_TENANT,
					"Please provide valid tenant id for booking creation");
		}

		Object mdmsData = mdmsUtil.mDMSCall(bookingRequest.getRequestInfo(), tenantId);

		// 1. Validate request master data to confirm it has only valid data in records
		bookingValidator.validateCreate(bookingRequest, mdmsData);

		// 2. Add fields that has custom logic like booking no, ids using UUID
		enrichmentService.enrichCreateBookingRequest(bookingRequest);

		// ENcrypt PII data of applicant
		encryptionService.encryptObject(bookingRequest);

	    demandService.createDemand(bookingRequest, mdmsData, true);

		// 4.Persist the request using persister service
		bookingRepository.saveBooking(bookingRequest);

		String draftId = bookingRequest.getBookingApplication().getDraftId();
		// 5

		String bookingId = bookingRequest.getBookingApplication().getBookingId();

		BookingDetail bookingDetails = encryptionService.decryptObject(bookingRequest.getBookingApplication(),
				bookingRequest.getRequestInfo());

		List<Map<String, Object>> draftData = bookingRepository.getDraftData(uuid);
		String draftIdFromDraft = (String) draftData.get(0).get("draft_id");

		bookingRepository.updateTimerBookingId(bookingId, bookingDetails.getBookingNo(), draftIdFromDraft);
		//bookingRepository.updateBookingSynchronously(bookingId, uuid, null,
				//BookingStatusEnum.PENDING_FOR_PAYMENT.toString());

		if (StringUtils.isNotBlank(draftId)) {
			log.info("Deleting draft entry for draft id: " + draftId);
			bookingRepository.deleteDraftApplication(draftId);
		}

		return bookingDetails;
	}

	@Override
	public List<BookingDetail> getBookingDetails(AdvertisementSearchCriteria advertisementSearchCriteria,
			RequestInfo info) {
//	BookingValidator.validateSearch(info, advertisementSearchCriteria);
		List<BookingDetail> bookingDetails = new ArrayList<BookingDetail>();
//	advertisementSearchCriteria  = addCreatedByMeToCriteria(advertisementSearchCriteria, info);

		log.info("loading data based on criteria" + advertisementSearchCriteria);

		if (advertisementSearchCriteria.getMobileNumber() != null
				|| advertisementSearchCriteria.getApplicantName() != null) {

			ApplicantDetail applicantDetail = ApplicantDetail.builder()
					.applicantMobileNo(advertisementSearchCriteria.getMobileNumber())
					.applicantName(advertisementSearchCriteria.getApplicantName()).build();
			BookingDetail bookingDetail = BookingDetail.builder().applicantDetail(applicantDetail).build();
			BookingRequest bookingRequest = BookingRequest.builder().bookingApplication(bookingDetail).requestInfo(info)
					.build();

			bookingDetail = encryptionService.encryptObject(bookingRequest);

			advertisementSearchCriteria.setMobileNumber(bookingDetail.getApplicantDetail().getApplicantMobileNo());
			advertisementSearchCriteria.setApplicantName(bookingDetail.getApplicantDetail().getApplicantName());

			log.info("loading data based on criteria after encrypting mobile no : " + advertisementSearchCriteria);

		}

		bookingDetails = bookingRepository.getBookingDetails(advertisementSearchCriteria);
		// Fetch remaining timer values for the booking details
		// paymentTimerService.getRemainingTimerValue(bookingDetails);

		if (CollectionUtils.isEmpty(bookingDetails)) {
			return bookingDetails;
		}
		bookingDetails = encryptionService.decryptObject(bookingDetails, info);

		return bookingDetails;
	}

	@Override
	public Integer getBookingCount(@Valid AdvertisementSearchCriteria criteria, @NonNull RequestInfo requestInfo) {
		criteria.setCountCall(true);
		Integer bookingCount = 0;

		// criteria = addCreatedByMeToCriteria(criteria, requestInfo);
		bookingCount = bookingRepository.getBookingCount(criteria);

		return bookingCount;
	}

	@Override
	public List<AdvertisementSlotAvailabilityDetail> getAdvertisementSlotAvailability(
			AdvertisementSlotSearchCriteria criteria, RequestInfo requestInfo) {

		List<AdvertisementSlotAvailabilityDetail> availabiltityDetails = bookingRepository
				.getAdvertisementSlotAvailability(criteria);
		log.info("Availabiltity details fetched from DB :" + availabiltityDetails);

		List<AdvertisementSlotAvailabilityDetail> availabiltityDetailsResponse = convertToAdvertisementAvailabilityResponse(
				criteria, availabiltityDetails, requestInfo);

		if (criteria.getIsTimerRequired()) {
			paymentTimerService.insertBookingIdForTimer(criteria, requestInfo, availabiltityDetailsResponse);
		}
		log.info("Availabiltity details response after updating status :" + availabiltityDetailsResponse);

		return availabiltityDetailsResponse;
	}

	private List<AdvertisementSlotAvailabilityDetail> convertToAdvertisementAvailabilityResponse(
			AdvertisementSlotSearchCriteria criteria, List<AdvertisementSlotAvailabilityDetail> availabiltityDetails,
			RequestInfo requestInfo) {

		List<AdvertisementSlotAvailabilityDetail> availabiltityDetailsResponse = new ArrayList<>();
		LocalDate startDate = BookingUtil.parseStringToLocalDate(criteria.getBookingStartDate());
		LocalDate endDate = BookingUtil.parseStringToLocalDate(criteria.getBookingEndDate());

		List<LocalDate> totalDates = new ArrayList<>();

		// Calculating list of dates for booking
		while (!startDate.isAfter(endDate)) {
			totalDates.add(startDate);
			startDate = startDate.plusDays(1);
		}

		// Enforcing the maximum booking days constraint
		if (totalDates.size() > 90) {
			throw new CustomException(BookingConstants.INVALID_BOOKING_DATE_RANGE,
					"Booking is not allowed for this number of days.");
		}

		// Create a slot availability detail for each date
		totalDates.forEach(date -> {
			availabiltityDetailsResponse.add(createAdvertisementSlotAvailabiltityDetail(criteria, date));
		});

		// Set advertisement status to 'BOOKED' if already booked
		availabiltityDetailsResponse.forEach(detail -> {
			if (availabiltityDetails.contains(detail)) {
				detail.setSlotStaus(BookingStatusEnum.BOOKED.toString());
				detail.setBookingId(criteria.getBookingId());
			}

			if (criteria.getBookingId() != null && criteria.getBookingId().equals(detail.getBookingId())) {
				detail.setSlotStaus(BookingStatusEnum.AVAILABLE.toString());
			}
		});

		updateSlotAvailaibilityStatus(availabiltityDetailsResponse, criteria, requestInfo);

		log.info("Availability details response after updating status: " + availabiltityDetailsResponse);

		return availabiltityDetailsResponse;
	}

	public void updateSlotAvailaibilityStatus(List<AdvertisementSlotAvailabilityDetail> availabiltityDetailsResponse, AdvertisementSlotSearchCriteria criteria,
			 RequestInfo requestInfo) {

		// Fetch already booked slots from the timer table
		List<AdvertisementSlotAvailabilityDetail> bookedSlots = bookingRepository.getBookedSlotsFromTimer(criteria);

		if (!bookedSlots.isEmpty()) {
			availabiltityDetailsResponse.forEach(detail -> {
				// Find the corresponding booked slot for this detail
				Optional<AdvertisementSlotAvailabilityDetail> matchedSlot = bookedSlots.stream()
						.filter(slot -> Objects.equals(slot.getAddType(), detail.getAddType())
								&& Objects.equals(slot.getLocation(), detail.getLocation())
								&& Objects.equals(slot.getFaceArea(), detail.getFaceArea())
								&& Objects.equals(slot.getNightLight(), detail.getNightLight())
								&& Objects.equals(slot.getBookingDate(), detail.getBookingDate()))
						.findFirst();

				// If a matching booked slot is found, update the status and set the UUID
				if (matchedSlot.isPresent()) {
					AdvertisementSlotAvailabilityDetail bookedSlot = matchedSlot.get();
					detail.setSlotStaus(BookingStatusEnum.BOOKED.toString());
					detail.setUuid(bookedSlot.getUuid()); // Set the UUID from the booked slot
				}

				// Check if the current user created the slot and set availability
				if (requestInfo.getUserInfo() != null && requestInfo.getUserInfo().getUuid() != null
						&& requestInfo.getUserInfo().getUuid().equals(detail.getUuid())) {
					detail.setSlotStaus(BookingStatusEnum.AVAILABLE.toString()); // Force it to AVAILABLE if the UUID
																					// matches
				}
			});
		}
	}

	private AdvertisementSlotAvailabilityDetail createAdvertisementSlotAvailabiltityDetail(
			AdvertisementSlotSearchCriteria criteria, LocalDate date) {
		AdvertisementSlotAvailabilityDetail availabiltityDetail = AdvertisementSlotAvailabilityDetail.builder()
				.addType(criteria.getAddType()).faceArea(criteria.getFaceArea()).location(criteria.getLocation())
				.nightLight(criteria.getNightLight()).slotStaus(BookingStatusEnum.AVAILABLE.toString())
				.tenantId(criteria.getTenantId()).bookingDate(BookingUtil.parseLocalDateToString(date, "yyyy-MM-dd"))
				.build();
		return availabiltityDetail;
	}

	// This method updates booking from the booking number, searches the booking num
	// and get its details, if payment detail is not null the it sets the receipt
	// number and payment date
	@Override
	public BookingDetail updateBooking(BookingRequest advertisementBookingRequest, PaymentDetail paymentDetail,
			BookingStatusEnum status) {
		String bookingNo = advertisementBookingRequest.getBookingApplication().getBookingNo();
		log.info("Updating booking for booking no : " + bookingNo);
		if (bookingNo == null) {
			return null;
		}
		AdvertisementSearchCriteria advertisementSearchCriteria = AdvertisementSearchCriteria.builder()
				.bookingNo(bookingNo).build();
		List<BookingDetail> bookingDetails = bookingRepository.getBookingDetails(advertisementSearchCriteria);
		if (bookingDetails.size() == 0) {
			throw new CustomException("INVALID_BOOKING_CODE",
					"Booking no not valid. Failed to update booking status for : " + bookingNo);
		}

//		String tenantId = bookingDetails.get(0).getTenantId();		
//		Object mdmsData = mdmsUtil.mDMSCall(advertisementBookingRequest.getRequestInfo(), tenantId);
//		bookingValidator.validateUpdate(advertisementBookingRequest.getBookingApplication(), mdmsData, advertisementBookingRequest.getBookingApplication().getBookingStatus());

		convertBookingRequest(advertisementBookingRequest, bookingDetails.get(0));

		enrichmentService.enrichUpdateBookingRequest(advertisementBookingRequest, status);

		// Update payment date and receipt no on successful payment when payment detail
		// object is received
		if (paymentDetail != null) {
			advertisementBookingRequest.getBookingApplication().setReceiptNo(paymentDetail.getReceiptNumber());
			advertisementBookingRequest.getBookingApplication().setPaymentDate(paymentDetail.getReceiptDate());
		}
		bookingRepository.updateBooking(advertisementBookingRequest);
		log.info("fetched booking detail and updated status "
				+ advertisementBookingRequest.getBookingApplication().getBookingStatus());
		return advertisementBookingRequest.getBookingApplication();
	}

	@Transactional
	public BookingDetail updateBookingSynchronously(BookingRequest advertisementBookingRequest,
			PaymentDetail paymentDetail, BookingStatusEnum status) {
		String bookingNo = advertisementBookingRequest.getBookingApplication().getBookingNo();
		log.info("Updating booking for booking no : " + bookingNo);
		if (bookingNo == null) {
			return null;
		}
		AdvertisementSearchCriteria advertisementSearchCriteria = AdvertisementSearchCriteria.builder()
				.bookingNo(bookingNo).build();
		List<BookingDetail> bookingDetails = bookingRepository.getBookingDetails(advertisementSearchCriteria);
		if (bookingDetails.size() == 0) {
			throw new CustomException("INVALID_BOOKING_CODE",
					"Booking no not valid. Failed to update booking status for : " + bookingNo);
		}

//		String tenantId = bookingDetails.get(0).getTenantId();		
//		Object mdmsData = mdmsUtil.mDMSCall(advertisementBookingRequest.getRequestInfo(), tenantId);
//		bookingValidator.validateUpdate(advertisementBookingRequest.getBookingApplication(), mdmsData, advertisementBookingRequest.getBookingApplication().getBookingStatus());

		convertBookingRequest(advertisementBookingRequest, bookingDetails.get(0));

		enrichmentService.enrichUpdateBookingRequest(advertisementBookingRequest, status);

		// Update payment date and receipt no on successful payment when payment detail
		// object is received
		if (paymentDetail != null) {
			advertisementBookingRequest.getBookingApplication().setReceiptNo(paymentDetail.getReceiptNumber());
			advertisementBookingRequest.getBookingApplication().setPaymentDate(paymentDetail.getReceiptDate());
		}

		bookingRepository.updateBookingSynchronously(advertisementBookingRequest);
		log.info("fetched booking detail and updated status "
				+ advertisementBookingRequest.getBookingApplication().getBookingStatus());
		return advertisementBookingRequest.getBookingApplication();
	}

	// This sets the paymennt receipt file store id and permission letter file store
	// id
	private void convertBookingRequest(BookingRequest advertisementbookingRequest, BookingDetail bookingDetailDB) {
		BookingDetail bookingDetailRequest = advertisementbookingRequest.getBookingApplication();
		if (bookingDetailDB.getPermissionLetterFilestoreId() == null
				&& bookingDetailRequest.getPermissionLetterFilestoreId() != null) {
			bookingDetailDB.setPermissionLetterFilestoreId(bookingDetailRequest.getPermissionLetterFilestoreId());
		}

		if (bookingDetailDB.getPaymentReceiptFilestoreId() == null
				&& bookingDetailRequest.getPaymentReceiptFilestoreId() != null) {
			bookingDetailDB.setPaymentReceiptFilestoreId(bookingDetailRequest.getPaymentReceiptFilestoreId());
		}
		advertisementbookingRequest.setBookingApplication(bookingDetailDB);
	}

	@Override
	public BookingDetail createAdvertisementDraftApplication(BookingRequest bookingRequest) {

		String draftId = bookingRequest.getBookingApplication().getDraftId();

		if (StringUtils.isNotBlank(draftId)) {

			// Update existing draft
			enrichmentService.enrichUpdateAdvertisementDraftApplicationRequest(bookingRequest);
			bookingRepository.updateDraftApplication(bookingRequest);
		} else {

			enrichmentService.enrichCreateAdvertisementDraftApplicationRequest(bookingRequest);
			List<Map<String, Object>> draftData = bookingRepository
					.getDraftData(bookingRequest.getRequestInfo().getUserInfo().getUuid());
			String draftIdInDraft = (String) draftData.get(0).get("draft_id");
			if (draftIdInDraft == null) {
				bookingRepository.saveDraftApplication(bookingRequest);
			}
		}

		// Return the enriched booking application object
		return bookingRequest.getBookingApplication();
	}

	@Override
	public List<BookingDetail> getAdvertisementDraftApplicationDetails(@NonNull RequestInfo requestInfo,
			@Valid AdvertisementSearchCriteria criteria) {
		return bookingRepository.getAdvertisementDraftApplications(requestInfo, criteria);
	}

	public String deleteAdvertisementDraft(String draftId) {

		if (StringUtils.isNotBlank(draftId)) {
			log.info("Deleting draft entry for draft id: " + draftId);
			bookingRepository.deleteDraftApplication(draftId);
		}
		return BookingConstants.DRAFT_DISCARDED;
	}

}

