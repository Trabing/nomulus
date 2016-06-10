// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.flows.domain;


import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.updateAutorenewRecurrenceEndTime;
import static google.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static google.registry.flows.domain.DomainFlowUtils.verifyPremiumNameIsNotBlocked;
import static google.registry.flows.domain.DomainFlowUtils.verifyUnitIsYears;
import static google.registry.model.domain.DomainResource.extendRegistrationWithCap;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.pricing.PricingEngineProxy.getDomainRenewCost;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Ref;

import google.registry.flows.EppException;
import google.registry.flows.ResourceTransferRequestFlow;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainCommand.Transfer;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.fee.FeeTransferExtension;
import google.registry.model.domain.fee.FeeTransferResponseExtension;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;

import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;

import java.util.HashSet;
import java.util.Set;

/**
 * An EPP flow that requests a transfer on a {@link DomainResource}.
 *
 * @error {@link google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link google.registry.flows.ResourceMutateFlow.ResourceToMutateDoesNotExistException}
 * @error {@link google.registry.flows.ResourceTransferRequestFlow.AlreadyPendingTransferException}
 * @error {@link google.registry.flows.ResourceTransferRequestFlow.MissingTransferRequestAuthInfoException}
 * @error {@link google.registry.flows.ResourceTransferRequestFlow.ObjectAlreadySponsoredException}
 * @error {@link google.registry.flows.SingleResourceFlow.ResourceStatusProhibitsOperationException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.CurrencyValueScaleException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredForPremiumNameException}
 * @error {@link DomainFlowUtils.PremiumNameBlockedException}
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 */
public class DomainTransferRequestFlow
    extends ResourceTransferRequestFlow<DomainResource, Transfer> {

  /** The time when the transfer will be server approved if no other action happens first. */
  private DateTime automaticTransferTime;

  /** A new one-time billing event for the renewal packaged as part of this transfer. */
  private BillingEvent.OneTime transferBillingEvent;

  /** A new autorenew billing event starting at the transfer time. */
  private BillingEvent.Recurring gainingClientAutorenewEvent;

  /** A new autorenew poll message starting at the transfer time. */
  private PollMessage.Autorenew gainingClientAutorenewPollMessage;

  /** The amount that this transfer will cost due to the implied renew. */
  private Money renewCost;

  /**
   * An optional extension from the client specifying how much they think the transfer should cost.
   */
  private FeeTransferExtension feeTransfer;

  @Override
  protected Duration getAutomaticTransferLength() {
    return Registry.get(existingResource.getTld()).getAutomaticTransferLength();
  }

  @Override
  protected final void initResourceTransferRequestFlow() {
    registerExtensions(FeeTransferExtension.class);
    feeTransfer = eppInput.getSingleExtension(FeeTransferExtension.class);
    // The "existingResource" field is loaded before this function is called, but it may be null if
    // the domain name specified is invalid or doesn't exist.  If that's the case, simply exit
    // early, and ResourceMutateFlow will later throw ResourceToMutateDoesNotExistException.
    if (existingResource == null) {
      return;
    }
    Registry registry = Registry.get(existingResource.getTld());
    automaticTransferTime = now.plus(registry.getAutomaticTransferLength());
    // Note that the gaining registrar is used to calculate the cost of the renewal.
    renewCost = getDomainRenewCost(targetId, now, command.getPeriod().getValue());
    transferBillingEvent = new BillingEvent.OneTime.Builder()
        .setReason(Reason.TRANSFER)
        .setTargetId(targetId)
        .setClientId(getClientId())
        .setCost(renewCost)
        .setPeriodYears(command.getPeriod().getValue())
        .setEventTime(automaticTransferTime)
        .setBillingTime(automaticTransferTime.plus(registry.getTransferGracePeriodLength()))
        .setParent(historyEntry)
        .build();
    DateTime newExpirationTime = extendRegistrationWithCap(
        automaticTransferTime,
        existingResource.getRegistrationExpirationTime(),
        command.getPeriod().getValue());
    gainingClientAutorenewEvent = new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(targetId)
        .setClientId(gainingClient.getId())
        .setEventTime(newExpirationTime)
        .setRecurrenceEndTime(END_OF_TIME)
        .setParent(historyEntry)
        .build();
    gainingClientAutorenewPollMessage = new PollMessage.Autorenew.Builder()
        .setTargetId(targetId)
        .setClientId(gainingClient.getId())
        .setEventTime(newExpirationTime)
        .setAutorenewEndTime(END_OF_TIME)
        .setMsg("Domain was auto-renewed.")
        .setParent(historyEntry)
        .build();
  }

  @Override
  protected final void verifyTransferRequestIsAllowed() throws EppException {
    verifyUnitIsYears(command.getPeriod());
    if (!superuser) {
      verifyPremiumNameIsNotBlocked(targetId, now, getClientId());
    }
    validateFeeChallenge(
        targetId, existingResource.getTld(), now, feeTransfer, renewCost);
    checkAllowedAccessToTld(getAllowedTlds(), existingResource.getTld());
  }

  @Override
  protected ImmutableList<? extends ResponseExtension> getTransferResponseExtensions() {
    if (feeTransfer != null) {
      return ImmutableList.of(new FeeTransferResponseExtension.Builder()
          .setCurrency(renewCost.getCurrencyUnit())
          .setFee(ImmutableList.of(Fee.create(renewCost.getAmount(), "renew")))
          .build());
    } else {
      return null;
    }
  }

  @Override
  protected void setTransferDataProperties(TransferData.Builder builder) {
    builder
        .setServerApproveBillingEvent(Ref.create(transferBillingEvent))
        .setServerApproveAutorenewEvent(Ref.create(gainingClientAutorenewEvent))
        .setServerApproveAutorenewPollMessage(Ref.create(gainingClientAutorenewPollMessage))
        .setExtendedRegistrationYears(command.getPeriod().getValue());
  }

  /**
   * When a transfer is requested, schedule a billing event and poll message for the automatic
   * approval case.
   *
   * <p>Note that the action time is AUTOMATIC_TRANSFER_DAYS in the future, matching the server
   * policy on automated approval of transfers. There is no equivalent grace period added; if the
   * transfer is implicitly approved, the resource will project a grace period on itself.
   */
  @Override
  protected Set<Key<? extends TransferServerApproveEntity>> getTransferServerApproveEntities() {
    ofy().save().<Object>entities(
        transferBillingEvent, gainingClientAutorenewEvent, gainingClientAutorenewPollMessage);
    // If there will be an autorenew between now and the automatic transfer time, and if the
    // autorenew grace period length is long enough that the domain will still be within it at the
    // automatic transfer time, then the transfer will subsume the autorenew so we need to write out
    // a cancellation for it.
    Set<Key<? extends TransferServerApproveEntity>> serverApproveEntities = new HashSet<>();
    DateTime expirationTime = existingResource.getRegistrationExpirationTime();
    Registry registry = Registry.get(existingResource.getTld());
    if (automaticTransferTime.isAfter(expirationTime) && automaticTransferTime.isBefore(
        expirationTime.plus(registry.getAutoRenewGracePeriodLength()))) {
      BillingEvent.Cancellation autorenewCancellation = new BillingEvent.Cancellation.Builder()
          .setReason(Reason.RENEW)
          .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
          .setTargetId(targetId)
          .setClientId(existingResource.getCurrentSponsorClientId())
          .setEventTime(automaticTransferTime)
          .setBillingTime(expirationTime.plus(registry.getAutoRenewGracePeriodLength()))
          .setRecurringEventRef(existingResource.getAutorenewBillingEvent())
          .setParent(historyEntry)
          .build();
      ofy().save().entity(autorenewCancellation);
      serverApproveEntities.add(Key.create(autorenewCancellation));
    }
    serverApproveEntities.add(Key.create(transferBillingEvent));
    serverApproveEntities.add(Key.create(gainingClientAutorenewEvent));
    serverApproveEntities.add(Key.create(gainingClientAutorenewPollMessage));
    return serverApproveEntities;
  }

  /** Close the old autorenew billing event and save a new one. */
  @Override
  protected final void modifyRelatedResources() throws EppException {
    // End the old autorenew event and poll message at the implicit transfer time. This may delete
    // the poll message if it has no events left.
    //
    // Note that this is still left on the domain as the autorenewBillingEvent because it is still
    // the current autorenew event until the transfer happens. If you read the domain after the
    // transfer occurs, then the logic in cloneProjectedAtTime() will move the
    // serverApproveAutoRenewEvent into the autoRenewEvent field.
    updateAutorenewRecurrenceEndTime(existingResource, automaticTransferTime);
  }

  @Override
  protected final HistoryEntry.Type getHistoryEntryType() {
    return HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST;
  }

  @Override
  protected final Period getCommandPeriod() {
    return command.getPeriod();
  }
}
