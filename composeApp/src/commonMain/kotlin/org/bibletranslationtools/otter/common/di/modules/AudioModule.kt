///**
// * Copyright (C) 2020-2024 Wycliffe Associates
// *
// * This file is part of Orature.
// *
// * Orature is free software: you can redistribute it and/or modify
// * it under the terms of the GNU General Public License as published by
// * the Free Software Foundation, either version 3 of the License, or
// * (at your option) any later version.
// *
// * Orature is distributed in the hope that it will be useful,
// * but WITHOUT ANY WARRANTY; without even the implied warranty of
// * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// * GNU General Public License for more details.
// *
// * You should have received a copy of the GNU General Public License
// * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
// */
//package org.bibletranslationtools.otter.common.di.modules
//
//import dagger.Module
//import dagger.Provides
//import javax.inject.Singleton
//
//@Module
//class AudioModule {
//
//    companion object {
//        val audioConnectionFactory = AudioConnectionFactory()
//    }
//
//    @Provides
//    fun providesRecorder(): IAudioRecorder = audioConnectionFactory.getRecorder()
//
//    @Provides
//    fun providesPlayer(): IAudioPlayer = audioConnectionFactory.getPlayer()
//
//    @Provides
//    fun providesConnectionFactory(): AudioConnectionFactory = audioConnectionFactory
//
//    @Provides
//    fun providesWavCreator(): IWaveFileCreator = WaveFileCreator()
//
//    @Provides
//    @Singleton
//    fun providesAudioDevice(): AudioDeviceProvider = AudioDeviceProvider(DEFAULT_AUDIO_FORMAT)
//}
